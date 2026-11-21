package com.zetheta.demo.services;

import com.zetheta.demo.models.Account;
import com.zetheta.demo.models.LedgerEntry;
import com.zetheta.demo.repositories.AccountRepository;
import com.zetheta.demo.repositories.LedgerEntryRepository;
import com.zetheta.demo.utils.HashUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
public class LedgerService {
    private static final long CHAIN_LOCK_KEY = 424242L;

    public record StatementRow(
            String entryId,
            String journalId,
            String entryType,
            BigDecimal amount,
            String currency,
            String status,
            String effectiveDate,
            String createdBy,
            BigDecimal runningBalance
    ) {}

    private final LedgerEntryRepository repository;
    private final AccountRepository accountRepository;
    private final JdbcTemplate jdbcTemplate;
    private volatile Instant lastTrialBalanceCheckAt;

    public LedgerService(LedgerEntryRepository repository,
                         AccountRepository accountRepository,
                         JdbcTemplate jdbcTemplate) {
        this.repository = repository;
        this.accountRepository = accountRepository;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public UUID postTransaction(List<LedgerEntry> entries) {
        if (entries == null || entries.size() < 2) {
            throw new IllegalArgumentException("Transaction failed: A journal entry requires at least 2 ledger lines.");
        }

        // 1. Validate amounts, entry types, and per-currency debit = credit
        Map<String, BigDecimal> debitsByCurrency = new HashMap<>();
        Map<String, BigDecimal> creditsByCurrency = new HashMap<>();

        for (LedgerEntry entry : entries) {
            if (entry.getAccount() == null || entry.getAccount().getId() == null) {
                throw new IllegalArgumentException("Transaction failed: Account is required on every entry.");
            }
            if (entry.getAmount() == null || entry.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
                throw new IllegalArgumentException("Transaction failed: Entry amount must be strictly greater than zero.");
            }
            BigDecimal scaledAmount = entry.getAmount().setScale(4, RoundingMode.HALF_UP);
            entry.setAmount(scaledAmount);

            String curr = (entry.getCurrency() == null || entry.getCurrency().isBlank())
                    ? "INR" : entry.getCurrency().trim().toUpperCase();
            entry.setCurrency(curr);

            switch (entry.getEntryType()) {
                case "DEBIT" -> debitsByCurrency.merge(curr, scaledAmount, BigDecimal::add);
                case "CREDIT" -> creditsByCurrency.merge(curr, scaledAmount, BigDecimal::add);
                case null, default -> throw new IllegalArgumentException("Transaction failed: Invalid entryType " + entry.getEntryType());
            }
        }

        Set<String> allCurrencies = new HashSet<>();
        allCurrencies.addAll(debitsByCurrency.keySet());
        allCurrencies.addAll(creditsByCurrency.keySet());
        for (String curr : allCurrencies) {
            BigDecimal d = debitsByCurrency.getOrDefault(curr, BigDecimal.ZERO);
            BigDecimal c = creditsByCurrency.getOrDefault(curr, BigDecimal.ZERO);
            if (d.compareTo(c) != 0) {
                throw new IllegalArgumentException("Transaction failed: Unbalanced " + curr
                        + " entries. Total Debits (" + d + ") != Total Credits (" + c + ")");
            }
        }

        // 2. Acquire pessimistic locks on accounts in deterministic UUID order (deadlock-free)
        List<UUID> sortedAccountIds = entries.stream()
                .map(e -> e.getAccount().getId())
                .distinct()
                .sorted()
                .toList();

        Map<UUID, Account> lockedAccounts = new HashMap<>();
        for (UUID accId : sortedAccountIds) {
            Account locked = accountRepository.findByIdWithLock(accId)
                    .orElseThrow(() -> new IllegalArgumentException("Account not found: " + accId));
            lockedAccounts.put(accId, locked);
        }

        // 3. Serialize hash-chain head updates via transactional advisory lock
        jdbcTemplate.queryForList("SELECT pg_advisory_xact_lock(?)", CHAIN_LOCK_KEY);
        String previousHash = getChainHead();
        UUID generatedJournalId = UUID.randomUUID();
        ZonedDateTime now = ZonedDateTime.now().truncatedTo(ChronoUnit.MICROS);
        Instant effectiveInstant = now.toInstant();

        Map<UUID, BigDecimal> runningBalances = new HashMap<>();

        for (LedgerEntry entry : entries) {
            Account account = lockedAccounts.get(entry.getAccount().getId());
            entry.setAccount(account);

            // Enforce account currency match
            String accountCurrency = account.getCurrency().trim().toUpperCase();
            if (!accountCurrency.equals(entry.getCurrency())) {
                throw new IllegalArgumentException("Currency mismatch: Entry currency " + entry.getCurrency()
                        + " does not match account " + account.getAccountCode() + " currency " + accountCurrency);
            }

            // Race-safe idempotency claim using idempotency_keys table (ON CONFLICT DO NOTHING)
            String idemKey = entry.getIdempotencyKey();
            if (idemKey == null || idemKey.isBlank()) {
                idemKey = UUID.randomUUID().toString();
                entry.setIdempotencyKey(idemKey);
            } else {
                String actorKey = (entry.getCreatedBy() == null || entry.getCreatedBy().isBlank())
                        ? "SYSTEM" : entry.getCreatedBy().trim();
                String reqHash = HashUtils.sha256(
                        account.getId() + "|" + entry.getEntryType() + "|" + entry.getAmount() + "|" + entry.getCurrency());
                int claimed = jdbcTemplate.update(
                        "INSERT INTO idempotency_keys (idempotency_key, user_id, endpoint, request_hash, status, response_status, response_body, expires_at) " +
                                "VALUES (?, ?, '/api/v1/transactions', ?, 'COMPLETED', 200, ?, NOW() + INTERVAL '24 hours') " +
                                "ON CONFLICT (user_id, idempotency_key) DO NOTHING",
                        idemKey, actorKey, reqHash, generatedJournalId.toString());
                if (claimed == 0 || repository.existsByIdempotencyKey(idemKey)) {
                    throw new IllegalStateException("Duplicate request rejected: Idempotency key already processed -> " + idemKey);
                }
            }

            // Per-currency balance sufficiency check
            BigDecimal currentBalance = runningBalances.computeIfAbsent(
                    account.getId(), id -> repository.calculateBalanceByCurrency(id, accountCurrency));

            boolean isAssetDecrease = "Asset".equals(account.getAccountType()) && "CREDIT".equals(entry.getEntryType());
            boolean isLiabilityDecrease = "Liability".equals(account.getAccountType()) && "DEBIT".equals(entry.getEntryType());

            if (isAssetDecrease || isLiabilityDecrease) {
                if (currentBalance.compareTo(entry.getAmount()) < 0) {
                    throw new IllegalArgumentException("Transaction failed: Insufficient balance in account "
                            + account.getAccountCode() + " (" + accountCurrency + "). Available: " + currentBalance);
                }
            }

            boolean normalDebit = "Asset".equals(account.getAccountType()) || "Expense".equals(account.getAccountType());
            BigDecimal delta = (normalDebit == "DEBIT".equals(entry.getEntryType()))
                    ? entry.getAmount() : entry.getAmount().negate();
            runningBalances.put(account.getId(), currentBalance.add(delta));

            entry.setJournalId(generatedJournalId);
            entry.setEffectiveDate(now);
            if (entry.getCreatedBy() == null || entry.getCreatedBy().isBlank()) {
                entry.setCreatedBy("SYSTEM");
            }
            if (entry.getEntryId() == null) {
                entry.setEntryId(UUID.randomUUID());
            }

            entry.setPreviousHash(previousHash);
            String computedHash = HashUtils.computeEntryHash(
                    previousHash,
                    entry.getEntryId(),
                    entry.getJournalId(),
                    account.getId(),
                    entry.getEntryType(),
                    entry.getAmount(),
                    entry.getCurrency(),
                    effectiveInstant,
                    entry.getCreatedBy()
            );

            entry.setHash(computedHash);
            previousHash = computedHash;
            entry.setStatus("POSTED");
        }

        repository.saveAllAndFlush(entries);

        // 4. Refresh materialized balance_snapshots cache
        for (Map.Entry<UUID, BigDecimal> bal : runningBalances.entrySet()) {
            Account acc = lockedAccounts.get(bal.getKey());
            jdbcTemplate.update(
                    "INSERT INTO balance_snapshots (account_id, currency, balance, updated_at) " +
                            "VALUES (?, ?, ?, NOW()) " +
                            "ON CONFLICT (account_id) DO UPDATE SET balance = EXCLUDED.balance, updated_at = NOW()",
                    bal.getKey(), acc.getCurrency().trim(), bal.getValue());
        }

        return generatedJournalId;
    }

    private String getChainHead() {
        List<String> heads = jdbcTemplate.queryForList(
                "SELECT hash FROM ledger_entries ORDER BY chain_seq DESC LIMIT 1", String.class);
        if (heads.isEmpty() || heads.getFirst() == null || heads.getFirst().isBlank()) {
            return HashUtils.GENESIS_HASH;
        }
        return heads.getFirst().trim();
    }

    @Transactional
    public UUID revertTransaction(UUID originalJournalId) {
        jdbcTemplate.queryForList("SELECT pg_advisory_xact_lock(hashtext(?))", "REV:" + originalJournalId);

        int claimed = jdbcTemplate.update(
                "INSERT INTO idempotency_keys (idempotency_key, user_id, endpoint, request_hash, status, response_status, response_body, expires_at) " +
                        "VALUES (?, 'REVERSAL_ENGINE', '/api/v1/reversals', ?, 'PROCESSING', 200, 'PENDING', NOW() + INTERVAL '24 hours') " +
                        "ON CONFLICT (user_id, idempotency_key) DO NOTHING",
                "REV-" + originalJournalId, HashUtils.sha256(originalJournalId.toString()));
        if (claimed == 0) {
            throw new IllegalStateException("Reversal rejected: Journal ID already reversed -> " + originalJournalId);
        }

        List<LedgerEntry> originalEntries = repository.findByJournalId(originalJournalId);
        if (originalEntries.isEmpty()) {
            throw new IllegalArgumentException("Transaction failed: Original journal not found -> " + originalJournalId);
        }

        List<LedgerEntry> reversalEntries = new ArrayList<>();
        BigDecimal totalDebit = BigDecimal.ZERO;
        for (int i = 0; i < originalEntries.size(); i++) {
            LedgerEntry orig = originalEntries.get(i);
            if ("DEBIT".equals(orig.getEntryType())) {
                totalDebit = totalDebit.add(orig.getAmount());
            }
            reversalEntries.add(buildMirrorEntry(orig, originalJournalId, i));
        }

        UUID reversalJournalId = postTransaction(reversalEntries);

        jdbcTemplate.update(
                "INSERT INTO reversals (original_journal_id, reversal_journal_id, reversal_idempotency_key, refund_type, fee_policy, refund_amount, reason) " +
                        "VALUES (?, ?, ?, 'FULL', 'FULL', ?, 'Full mirror reversal')",
                originalJournalId, reversalJournalId, "REV-" + originalJournalId, totalDebit);

        jdbcTemplate.update(
                "UPDATE idempotency_keys SET status = 'COMPLETED', response_body = ? WHERE user_id = 'REVERSAL_ENGINE' AND idempotency_key = ?",
                reversalJournalId.toString(), "REV-" + originalJournalId);

        return reversalJournalId;
    }

    private LedgerEntry buildMirrorEntry(LedgerEntry original, UUID originalJournalId, int index) {
        LedgerEntry reversal = new LedgerEntry();
        reversal.setAccount(original.getAccount());
        reversal.setAmount(original.getAmount());
        reversal.setCurrency(original.getCurrency().trim());
        reversal.setCreatedBy("REVERSAL_ENGINE");
        reversal.setIdempotencyKey("REV-" + originalJournalId + "-" + index);
        reversal.setEntryType("DEBIT".equals(original.getEntryType()) ? "CREDIT" : "DEBIT");
        return reversal;
    }

    public String verifyAuditTrail() {
        var rows = jdbcTemplate.queryForList(
                "SELECT entry_id, journal_id, account_id, entry_type, amount, currency, " +
                        "effective_date, created_by, previous_hash, hash " +
                        "FROM ledger_entries ORDER BY chain_seq ASC");

        String expectedPreviousHash = HashUtils.GENESIS_HASH;
        int verifiedCount = 0;

        for (Map<String, Object> row : rows) {
            UUID entryId = (UUID) row.get("entry_id");
            String rawPrev = (String) row.get("previous_hash");
            String actualPreviousHash = rawPrev == null ? "" : rawPrev.trim();

            if (!expectedPreviousHash.equals(actualPreviousHash)) {
                return "TAMPERING DETECTED! Broken chain link at Entry ID: " + entryId
                        + " (expected previousHash=" + expectedPreviousHash + ", actual=" + actualPreviousHash + ")";
            }

            Instant effectiveInstant = extractInstant(row.get("effective_date"));
            String currency = row.get("currency") == null ? "" : row.get("currency").toString().trim();

            String recalculatedHash = HashUtils.computeEntryHash(
                    actualPreviousHash,
                    entryId,
                    (UUID) row.get("journal_id"),
                    (UUID) row.get("account_id"),
                    (String) row.get("entry_type"),
                    (BigDecimal) row.get("amount"),
                    currency,
                    effectiveInstant,
                    (String) row.get("created_by")
            );

            String rawHash = (String) row.get("hash");
            String storedHash = rawHash == null ? "" : rawHash.trim();
            if (!recalculatedHash.equals(storedHash)) {
                return "TAMPERING DETECTED! Row corrupted at Entry ID: " + entryId;
            }

            expectedPreviousHash = storedHash;
            verifiedCount++;
        }

        return "AUDIT PASSED: " + verifiedCount + " cryptographic hashes verified securely. Zero tampering detected.";
    }

    private Instant extractInstant(Object rawTs) {
        return switch (rawTs) {
            case null -> null;
            case Timestamp ts -> ts.toInstant().truncatedTo(ChronoUnit.MICROS);
            case OffsetDateTime odt -> odt.toInstant().truncatedTo(ChronoUnit.MICROS);
            case Instant inst -> inst.truncatedTo(ChronoUnit.MICROS);
            default -> Instant.parse(rawTs.toString()).truncatedTo(ChronoUnit.MICROS);
        };
    }

    @Transactional
    public UUID transferFunds(UUID fromAccountId, UUID toAccountId, BigDecimal amount) {
        if (fromAccountId.equals(toAccountId)) {
            throw new IllegalArgumentException("Transfer failed: Sender and receiver accounts must be different.");
        }

        UUID firstLock = fromAccountId.compareTo(toAccountId) < 0 ? fromAccountId : toAccountId;
        UUID secondLock = fromAccountId.compareTo(toAccountId) < 0 ? toAccountId : fromAccountId;

        accountRepository.findByIdWithLock(firstLock)
                .orElseThrow(() -> new IllegalArgumentException("Account not found: " + firstLock));
        accountRepository.findByIdWithLock(secondLock)
                .orElseThrow(() -> new IllegalArgumentException("Account not found: " + secondLock));

        Account fromAccount = accountRepository.findById(fromAccountId).orElseThrow();
        Account toAccount = accountRepository.findById(toAccountId).orElseThrow();

        if (!fromAccount.getCurrency().trim().equalsIgnoreCase(toAccount.getCurrency().trim())) {
            throw new IllegalArgumentException("Transfer failed: Cross-currency transfers must use FX conversion.");
        }

        LedgerEntry creditSender = new LedgerEntry();
        creditSender.setAccount(fromAccount);
        creditSender.setEntryType("CREDIT");
        creditSender.setAmount(amount);
        creditSender.setCurrency(fromAccount.getCurrency().trim());
        creditSender.setCreatedBy("SYSTEM_P2P");

        LedgerEntry debitReceiver = new LedgerEntry();
        debitReceiver.setAccount(toAccount);
        debitReceiver.setEntryType("DEBIT");
        debitReceiver.setAmount(amount);
        debitReceiver.setCurrency(toAccount.getCurrency().trim());
        debitReceiver.setCreatedBy("SYSTEM_P2P");

        return postTransaction(Arrays.asList(creditSender, debitReceiver));
    }

    public Map<String, Object> getTrialBalance(ZonedDateTime asOfDate, String currencyFilter) {
        ZonedDateTime cutoff = (asOfDate == null) ? ZonedDateTime.now().plusYears(1) : asOfDate;
        String filter = (currencyFilter == null || currencyFilter.isBlank())
                ? null : currencyFilter.trim().toUpperCase();

        List<Object[]> rows = repository.generateTrialBalanceAsOf(cutoff);
        List<Object> accounts = new ArrayList<>();
        Map<String, BigDecimal> debitsByCurr = new LinkedHashMap<>();
        Map<String, BigDecimal> creditsByCurr = new LinkedHashMap<>();

        for (Object[] row : rows) {
            String curr = row[3].toString().trim().toUpperCase();
            if (filter != null && !filter.equals(curr)) {
                continue;
            }
            BigDecimal debits = (BigDecimal) row[4];
            BigDecimal credits = (BigDecimal) row[5];
            BigDecimal netBalance = (BigDecimal) row[6];

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("accountCode", row[0]);
            item.put("accountName", row[1]);
            item.put("accountType", row[2]);
            item.put("currency", curr);
            item.put("totalDebits", debits);
            item.put("totalCredits", credits);
            item.put("netBalance", netBalance);
            accounts.add(item);

            debitsByCurr.merge(curr, debits, BigDecimal::add);
            creditsByCurr.merge(curr, credits, BigDecimal::add);
        }

        List<Object> currencySummaries = new ArrayList<>();
        boolean allBalanced = true;
        for (String curr : debitsByCurr.keySet()) {
            BigDecimal d = debitsByCurr.getOrDefault(curr, BigDecimal.ZERO);
            BigDecimal c = creditsByCurr.getOrDefault(curr, BigDecimal.ZERO);
            boolean balanced = d.compareTo(c) == 0;
            if (!balanced) {
                allBalanced = false;
            }
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("currency", curr);
            summary.put("totalDebits", d);
            summary.put("totalCredits", c);
            summary.put("difference", d.subtract(c));
            summary.put("balanced", balanced);
            currencySummaries.add(summary);
        }

        this.lastTrialBalanceCheckAt = Instant.now();

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("report", "Trial Balance");
        report.put("asOfDate", cutoff.toString());
        report.put("currency", filter == null ? "ALL" : filter);
        report.put("accounts", accounts);
        report.put("currencySummaries", currencySummaries);
        report.put("balanced", allBalanced);
        report.put("generatedAt", ZonedDateTime.now().toString());
        return report;
    }

    public Map<String, Object> getAccountStatement(UUID accountId, ZonedDateTime from, ZonedDateTime to,
                                                   int page, int size) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new IllegalArgumentException("Account not found: " + accountId));

        List<StatementRow> allMatching = computeStatementWithRunningBalance(account, from, to);

        int safePage = Math.max(0, page);
        int safeSize = Math.clamp(size, 1, 500);
        int totalElements = allMatching.size();
        int totalPages = totalElements == 0 ? 0 : (int) Math.ceil((double) totalElements / safeSize);
        int fromIdx = Math.min(safePage * safeSize, totalElements);
        int toIdx = Math.min(fromIdx + safeSize, totalElements);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("accountId", account.getId().toString());
        response.put("accountCode", account.getAccountCode());
        response.put("accountName", account.getAccountName());
        response.put("accountType", account.getAccountType());
        response.put("currency", account.getCurrency().trim());
        response.put("page", safePage);
        response.put("size", safeSize);
        response.put("totalElements", totalElements);
        response.put("totalPages", totalPages);
        response.put("entries", allMatching.subList(fromIdx, toIdx));
        return response;
    }

    public String exportAccountStatementCsv(UUID accountId, ZonedDateTime from, ZonedDateTime to) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new IllegalArgumentException("Account not found: " + accountId));

        List<StatementRow> rows = computeStatementWithRunningBalance(account, from, to);
        StringBuilder csv = new StringBuilder(
                "entry_id,journal_id,entry_type,amount,currency,status,effective_date,created_by,running_balance\n");
        for (StatementRow row : rows) {
            csv.append(row.entryId()).append(",")
                    .append(row.journalId()).append(",")
                    .append(row.entryType()).append(",")
                    .append(row.amount()).append(",")
                    .append(row.currency()).append(",")
                    .append(row.status()).append(",")
                    .append(row.effectiveDate()).append(",")
                    .append(row.createdBy()).append(",")
                    .append(row.runningBalance()).append("\n");
        }
        return csv.toString();
    }

    private List<StatementRow> computeStatementWithRunningBalance(Account account,
                                                                  ZonedDateTime from,
                                                                  ZonedDateTime to) {
        List<LedgerEntry> chronological = repository.findByAccount_IdOrderByEffectiveDateAsc(account.getId());
        boolean normalDebit = "Asset".equals(account.getAccountType()) || "Expense".equals(account.getAccountType());
        BigDecimal running = BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
        List<StatementRow> filtered = new ArrayList<>();

        for (LedgerEntry e : chronological) {
            BigDecimal delta = (normalDebit == "DEBIT".equals(e.getEntryType()))
                    ? e.getAmount() : e.getAmount().negate();
            running = running.add(delta);

            boolean afterStart = (from == null) || !e.getEffectiveDate().isBefore(from);
            boolean beforeEnd = (to == null) || !e.getEffectiveDate().isAfter(to);
            if (afterStart && beforeEnd) {
                filtered.add(new StatementRow(
                        e.getEntryId().toString(),
                        e.getJournalId().toString(),
                        e.getEntryType(),
                        e.getAmount(),
                        e.getCurrency().trim(),
                        e.getStatus(),
                        e.getEffectiveDate().toString(),
                        e.getCreatedBy(),
                        running
                ));
            }
        }
        return filtered;
    }

    public List<String> getLedgerPartitionNames() {
        return jdbcTemplate.queryForList(
                "SELECT c.relname FROM pg_inherits i " +
                        "JOIN pg_class p ON i.inhparent = p.oid " +
                        "JOIN pg_class c ON i.inhrelid = c.oid " +
                        "WHERE p.relname = 'ledger_entries' ORDER BY c.relname",
                String.class);
    }

    public String getLastTrialBalanceCheckAt() {
        if (lastTrialBalanceCheckAt == null) {
            getTrialBalance(null, null);
        }
        return lastTrialBalanceCheckAt.toString();
    }

    public Map<String, Object> getIncomeStatement() {
        return getIncomeStatement(ZonedDateTime.now().minusYears(10), ZonedDateTime.now().plusYears(1), "INR");
    }

    public Map<String, Object> getIncomeStatement(ZonedDateTime from, ZonedDateTime to, String currency) {
        String curr = (currency == null || currency.isBlank()) ? "INR" : currency.trim().toUpperCase();
        ZonedDateTime start = (from == null) ? ZonedDateTime.now().minusYears(10) : from;
        ZonedDateTime end = (to == null) ? ZonedDateTime.now().plusYears(1) : to;

        BigDecimal totalRevenue = BigDecimal.ZERO;
        BigDecimal totalExpenses = BigDecimal.ZERO;

        for (Object[] row : repository.getAccountTypeTotalsByPeriod(start, end, curr)) {
            String type = (String) row[0];
            BigDecimal debits = (BigDecimal) row[1];
            BigDecimal credits = (BigDecimal) row[2];
            if ("Revenue".equalsIgnoreCase(type)) {
                totalRevenue = credits.subtract(debits);
            } else if ("Expense".equalsIgnoreCase(type)) {
                totalExpenses = debits.subtract(credits);
            }
        }

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("report", "Income Statement (P&L)");
        report.put("currency", curr);
        report.put("from", start.toString());
        report.put("to", end.toString());
        report.put("totalRevenue", totalRevenue);
        report.put("totalExpenses", totalExpenses);
        report.put("netIncome", totalRevenue.subtract(totalExpenses));
        report.put("generatedAt", ZonedDateTime.now().toString());
        return report;
    }

    public Map<String, Object> getBalanceSheet() {
        return getBalanceSheet(ZonedDateTime.now().plusYears(1), "INR");
    }

    public Map<String, Object> getBalanceSheet(ZonedDateTime asOfDate, String currency) {
        String curr = (currency == null || currency.isBlank()) ? "INR" : currency.trim().toUpperCase();
        ZonedDateTime cutoff = (asOfDate == null) ? ZonedDateTime.now().plusYears(1) : asOfDate;

        BigDecimal assets = BigDecimal.ZERO;
        BigDecimal liabilities = BigDecimal.ZERO;
        BigDecimal equity = BigDecimal.ZERO;
        BigDecimal revenue = BigDecimal.ZERO;
        BigDecimal expenses = BigDecimal.ZERO;

        for (Object[] row : repository.getAccountTypeTotalsAsOf(cutoff, curr)) {
            String type = (String) row[0];
            BigDecimal debits = (BigDecimal) row[1];
            BigDecimal credits = (BigDecimal) row[2];
            switch (type) {
                case "Asset" -> assets = debits.subtract(credits);
                case "Liability" -> liabilities = credits.subtract(debits);
                case "Equity" -> equity = credits.subtract(debits);
                case "Revenue" -> revenue = credits.subtract(debits);
                case "Expense" -> expenses = debits.subtract(credits);
            }
        }

        BigDecimal netIncome = revenue.subtract(expenses);
        BigDecimal totalEquityWithNetIncome = equity.add(netIncome);
        BigDecimal liabilitiesPlusEquity = liabilities.add(totalEquityWithNetIncome);

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("report", "Balance Sheet (A = L + E)");
        report.put("currency", curr);
        report.put("asOfDate", cutoff.toString());
        report.put("totalAssets", assets);
        report.put("totalLiabilities", liabilities);
        report.put("totalEquity", totalEquityWithNetIncome);
        report.put("liabilitiesPlusEquity", liabilitiesPlusEquity);
        report.put("accountingEquationBalanced", assets.compareTo(liabilitiesPlusEquity) == 0);
        report.put("generatedAt", ZonedDateTime.now().toString());
        return report;
    }
}