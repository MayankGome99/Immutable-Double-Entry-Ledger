package com.zetheta.demo.services;

import com.zetheta.demo.models.Account;
import com.zetheta.demo.models.LedgerEntry;
import com.zetheta.demo.repositories.AccountRepository;
import com.zetheta.demo.repositories.LedgerEntryRepository;
import com.zetheta.demo.utils.HashUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.ZonedDateTime;
import java.util.*;

@Service
public class LedgerService {

    private final LedgerEntryRepository repository;
    private final AccountRepository accountRepository;

    public LedgerService(LedgerEntryRepository repository, AccountRepository accountRepository) {
        this.repository = repository;
        this.accountRepository = accountRepository;
    }

    @Transactional
    public UUID postTransaction(List<LedgerEntry> entries) {
        if (entries == null || entries.size() < 2) {
            throw new IllegalArgumentException("Transaction failed: A journal entry requires at least 2 ledger lines.");
        }

        BigDecimal totalDebits = BigDecimal.ZERO;
        BigDecimal totalCredits = BigDecimal.ZERO;

        for (LedgerEntry entry : entries) {
            if (entry.getAmount() == null || entry.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
                throw new IllegalArgumentException("Transaction failed: Entry amount must be strictly greater than zero.");
            }
            if (entry.getIdempotencyKey() != null && !entry.getIdempotencyKey().isBlank()) {
                if (repository.existsByIdempotencyKey(entry.getIdempotencyKey())) {
                    throw new IllegalStateException("Duplicate request rejected: Idempotency key already processed -> " + entry.getIdempotencyKey());
                }
            }
            if ("DEBIT".equals(entry.getEntryType())) {
                totalDebits = totalDebits.add(entry.getAmount());
            } else if ("CREDIT".equals(entry.getEntryType())) {
                totalCredits = totalCredits.add(entry.getAmount());
            } else {
                throw new IllegalArgumentException("Transaction failed: Invalid entryType " + entry.getEntryType());
            }
        }

        if (totalDebits.compareTo(totalCredits) != 0) {
            throw new IllegalArgumentException("Transaction failed: Total Debits (" + totalDebits + ") do not match Total Credits (" + totalCredits + ")");
        }

        // Sort account IDs to acquire locks in deterministic order and prevent deadlocks
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

        String previousHash = "GENESIS_HASH";
        UUID generatedJournalId = UUID.randomUUID();
        ZonedDateTime now = ZonedDateTime.now();

        for (LedgerEntry entry : entries) {
            Account account = lockedAccounts.get(entry.getAccount().getId());
            entry.setAccount(account);

            boolean isAssetDecrease = "Asset".equals(account.getAccountType()) && "CREDIT".equals(entry.getEntryType());
            boolean isLiabilityDecrease = "Liability".equals(account.getAccountType()) && "DEBIT".equals(entry.getEntryType());

            if (isAssetDecrease || isLiabilityDecrease) {
                BigDecimal currentBalance = repository.calculateBalance(account.getId());
                if (currentBalance.compareTo(entry.getAmount()) < 0) {
                    throw new IllegalArgumentException("Transaction failed: Insufficient balance in account "
                            + account.getAccountCode() + ". Available: " + currentBalance);
                }
            }

            entry.setJournalId(generatedJournalId);
            entry.setEffectiveDate(now);

            if (entry.getCreatedBy() == null || entry.getCreatedBy().isBlank()) {
                entry.setCreatedBy("SYSTEM");
            }
            if (entry.getIdempotencyKey() == null || entry.getIdempotencyKey().isBlank()) {
                entry.setIdempotencyKey(UUID.randomUUID().toString());
            }
            if (entry.getCurrency() == null || entry.getCurrency().isBlank()) {
                entry.setCurrency("INR");
            }

            String cleanAmount = entry.getAmount().stripTrailingZeros().toPlainString();
            String rawData = entry.getJournalId().toString() + entry.getEntryType() + cleanAmount + previousHash.trim();

            entry.setHash(HashUtils.generateSHA256(rawData));
            entry.setPreviousHash(previousHash);

            previousHash = entry.getHash();
            entry.setStatus("POSTED");
        }

        repository.saveAll(entries);
        return generatedJournalId;
    }

    @Transactional
    public UUID revertTransaction(UUID originalJournalId) {
        if (repository.existsByIdempotencyKey("REV-" + originalJournalId + "-0")) {
            throw new IllegalStateException("Reversal rejected: Journal ID already reversed -> " + originalJournalId);
        }

        List<LedgerEntry> originalEntries = repository.findByJournalId(originalJournalId);
        if (originalEntries.isEmpty()) {
            throw new IllegalArgumentException("Transaction failed: Original journal not found -> " + originalJournalId);
        }

        List<LedgerEntry> reversalEntries = new ArrayList<>();
        for (int i = 0; i < originalEntries.size(); i++) {
            LedgerEntry original = originalEntries.get(i);
            LedgerEntry reversal = new LedgerEntry();
            reversal.setAccount(original.getAccount());
            reversal.setAmount(original.getAmount());
            reversal.setCurrency(original.getCurrency().trim());
            reversal.setCreatedBy("REVERSAL_ENGINE");
            reversal.setIdempotencyKey("REV-" + originalJournalId + "-" + i);
            reversal.setEntryType("DEBIT".equals(original.getEntryType()) ? "CREDIT" : "DEBIT");
            reversalEntries.add(reversal);
        }

        return postTransaction(reversalEntries);
    }

    public String verifyAuditTrail() {
        List<LedgerEntry> allEntries = repository.findAll();
        int verifiedCount = 0;

        for (LedgerEntry entry : allEntries) {
            String cleanAmount = entry.getAmount().stripTrailingZeros().toPlainString();
            String rawData = entry.getJournalId().toString() + entry.getEntryType() + cleanAmount + entry.getPreviousHash().trim();
            String recalculatedHash = HashUtils.generateSHA256(rawData);

            if (!recalculatedHash.equals(entry.getHash().trim())) {
                return "TAMPERING DETECTED! Row corrupted at Entry ID: " + entry.getEntryId();
            }
            verifiedCount++;
        }

        return "AUDIT PASSED: " + verifiedCount + " cryptographic hashes verified securely. Zero tampering detected.";
    }

    @Transactional
    public UUID transferFunds(UUID fromAccountId, UUID toAccountId, BigDecimal amount) {
        if (fromAccountId.equals(toAccountId)) {
            throw new IllegalArgumentException("Transfer failed: Sender and receiver accounts must be different.");
        }

        // Lock in deterministic UUID order to prevent deadlocks
        UUID firstLock = fromAccountId.compareTo(toAccountId) < 0 ? fromAccountId : toAccountId;
        UUID secondLock = fromAccountId.compareTo(toAccountId) < 0 ? toAccountId : fromAccountId;

        accountRepository.findByIdWithLock(firstLock)
                .orElseThrow(() -> new IllegalArgumentException("Account not found: " + firstLock));
        accountRepository.findByIdWithLock(secondLock)
                .orElseThrow(() -> new IllegalArgumentException("Account not found: " + secondLock));

        Account fromAccount = accountRepository.findById(fromAccountId).orElseThrow();
        Account toAccount = accountRepository.findById(toAccountId).orElseThrow();

        // Standard Double-Entry for Asset wallets: Decrease sender (CREDIT), Increase receiver (DEBIT)
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

    public Map<String, Object> getIncomeStatement() {
        BigDecimal totalRevenue = BigDecimal.ZERO;
        BigDecimal totalExpenses = BigDecimal.ZERO;

        for (Object[] row : repository.getAccountTypeTotals()) {
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
        report.put("totalRevenue", totalRevenue);
        report.put("totalExpenses", totalExpenses);
        report.put("netIncome", totalRevenue.subtract(totalExpenses));
        report.put("generatedAt", ZonedDateTime.now().toString());
        return report;
    }

    public Map<String, Object> getBalanceSheet() {
        BigDecimal assets = BigDecimal.ZERO;
        BigDecimal liabilities = BigDecimal.ZERO;
        BigDecimal equity = BigDecimal.ZERO;
        BigDecimal revenue = BigDecimal.ZERO;
        BigDecimal expenses = BigDecimal.ZERO;

        for (Object[] row : repository.getAccountTypeTotals()) {
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
        report.put("totalAssets", assets);
        report.put("totalLiabilities", liabilities);
        report.put("totalEquity", totalEquityWithNetIncome);
        report.put("liabilitiesPlusEquity", liabilitiesPlusEquity);
        report.put("accountingEquationBalanced", assets.compareTo(liabilitiesPlusEquity) == 0);
        report.put("generatedAt", ZonedDateTime.now().toString());
        return report;
    }
}