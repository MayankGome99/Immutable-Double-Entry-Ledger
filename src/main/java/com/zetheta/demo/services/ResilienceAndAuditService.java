package com.zetheta.demo.services;

import com.zetheta.demo.models.Account;
import com.zetheta.demo.models.ExchangeRateSnapshot;
import com.zetheta.demo.models.LedgerEntry;
import com.zetheta.demo.repositories.AccountRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class ResilienceAndAuditService {

    private final AccountRepository accountRepository;
    private final LedgerService ledgerService;
    private final ExchangeRateService exchangeRateService;
    private final JdbcTemplate jdbcTemplate;

    private final Map<String, String> circuitState = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> failureCounts = new ConcurrentHashMap<>();
    private final Map<String, Instant> openedAt = new ConcurrentHashMap<>();
    private static final int FAILURE_THRESHOLD = 5;
    private static final long COOLDOWN_SECONDS = 30;

    public ResilienceAndAuditService(AccountRepository accountRepository,
                                     LedgerService ledgerService,
                                     ExchangeRateService exchangeRateService,
                                     JdbcTemplate jdbcTemplate) {
        this.accountRepository = accountRepository;
        this.ledgerService = ledgerService;
        this.exchangeRateService = exchangeRateService;
        this.jdbcTemplate = jdbcTemplate;
        circuitState.put("FX_RATE_PROVIDER", "CLOSED");
        circuitState.put("PAYMENT_GATEWAY", "CLOSED");
        circuitState.put("BANK_TRANSFER_API", "CLOSED");
    }

    // 1. Nightly & On-Demand Unrealised FX P&L Revaluation (Section A3.3)
    @Scheduled(cron = "0 55 23 * * *")
    @Transactional
    public Map<String, Object> runUnrealisedFxRevaluation(BigDecimal baselineUsdRate) {
        ExchangeRateSnapshot closing = exchangeRateService.getValidRate("USD", "INR");
        Account usdWallet = findByCode("1002");

        BigDecimal usdBalance = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(CASE WHEN entry_type = 'DEBIT' THEN amount ELSE -amount END), 0) " +
                        "FROM ledger_entries WHERE account_id = ?",
                BigDecimal.class, usdWallet.getId());
        if (usdBalance == null) {
            usdBalance = BigDecimal.ZERO;
        }

        BigDecimal base = (baselineUsdRate == null) ? new BigDecimal("83.00000000") : baselineUsdRate;
        BigDecimal rateDiff = closing.getRate().subtract(base);
        BigDecimal unrealisedPnl = usdBalance.abs().multiply(rateDiff).setScale(4, RoundingMode.HALF_UP);

        UUID journalId = null;
        String pnlType = "NONE";

        if (unrealisedPnl.compareTo(BigDecimal.ZERO) > 0) {
            pnlType = "UNREALISED_GAIN";
            journalId = ledgerService.postTransaction(Arrays.asList(
                    buildEntry("1004", "DEBIT", unrealisedPnl, "INR", "FX_REVAL_JOB"),
                    buildEntry("4003", "CREDIT", unrealisedPnl, "INR", "FX_REVAL_JOB")
            ));
        } else if (unrealisedPnl.compareTo(BigDecimal.ZERO) < 0) {
            pnlType = "UNREALISED_LOSS";
            BigDecimal absLoss = unrealisedPnl.abs();
            journalId = ledgerService.postTransaction(Arrays.asList(
                    buildEntry("5010", "DEBIT", absLoss, "INR", "FX_REVAL_JOB"),
                    buildEntry("1004", "CREDIT", absLoss, "INR", "FX_REVAL_JOB")
            ));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("job", "UNREALISED_FX_REVALUATION");
        result.put("usdHoldings", usdBalance);
        result.put("baselineRate", base);
        result.put("closingRate", closing.getRate());
        result.put("pnlType", pnlType);
        result.put("unrealisedPnlInr", unrealisedPnl);
        result.put("journalId", journalId == null ? "NO_MOVEMENT" : journalId.toString());
        result.put("executedAt", ZonedDateTime.now().toString());
        return result;
    }

    // 2. Audit Anomaly Detection (Day 11 & Wirecard Case Study)
    public Map<String, Object> detectAnomalies(BigDecimal largeThreshold) {
        BigDecimal threshold = (largeThreshold == null) ? new BigDecimal("10000.00") : largeThreshold;

        List<Object> flagged = new ArrayList<>();
        jdbcTemplate.query(
                "SELECT entry_id, journal_id, entry_type, amount, currency, created_by, posted_at " +
                        "FROM ledger_entries " +
                        "WHERE (amount >= ? AND MOD(amount, 1000) = 0) " +
                        "OR EXTRACT(HOUR FROM posted_at AT TIME ZONE 'UTC') >= 22 " +
                        "OR EXTRACT(HOUR FROM posted_at AT TIME ZONE 'UTC') < 5 " +
                        "ORDER BY posted_at DESC LIMIT 50",
                rs -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("entryId", rs.getObject("entry_id").toString());
                    row.put("journalId", rs.getObject("journal_id").toString());
                    row.put("entryType", rs.getString("entry_type"));
                    row.put("amount", rs.getBigDecimal("amount"));
                    row.put("currency", rs.getString("currency").trim());
                    row.put("createdBy", rs.getString("created_by"));
                    row.put("reason", "LARGE_ROUND_AMOUNT_OR_AFTER_HOURS");
                    flagged.add(row);
                },
                threshold
        );

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("report", "Audit Anomaly Detection");
        report.put("thresholdAmount", threshold);
        report.put("anomaliesDetected", flagged.size());
        report.put("flaggedEntries", flagged);
        report.put("checkedAt", ZonedDateTime.now().toString());
        return report;
    }

    // 3. Second Concurrency Control Strategy: PostgreSQL Advisory Lock (Section A4.3 & Day 7)
    @Transactional
    public UUID withdrawWithAdvisoryLock(UUID walletAccountId, BigDecimal amount, String actor) {
        jdbcTemplate.query("SELECT pg_advisory_xact_lock(hashtext(?))", rs -> {}, walletAccountId.toString());

        Account wallet = accountRepository.findById(walletAccountId)
                .orElseThrow(() -> new IllegalArgumentException("Account not found: " + walletAccountId));
        Account depositLiability = findByCode("2001");

        LedgerEntry debitLiability = new LedgerEntry();
        debitLiability.setAccount(depositLiability);
        debitLiability.setEntryType("DEBIT");
        debitLiability.setAmount(amount);
        debitLiability.setCurrency("INR");
        debitLiability.setCreatedBy(actor);

        LedgerEntry creditWallet = new LedgerEntry();
        creditWallet.setAccount(wallet);
        creditWallet.setEntryType("CREDIT");
        creditWallet.setAmount(amount);
        creditWallet.setCurrency("INR");
        creditWallet.setCreatedBy(actor);

        return ledgerService.postTransaction(Arrays.asList(debitLiability, creditWallet));
    }

    // 4. Circuit Breaker Pattern for External Dependencies (Section A10.4)
    public void recordDependencyFailure(String dependency) {
        int count = failureCounts.computeIfAbsent(dependency, k -> new AtomicInteger(0)).incrementAndGet();
        if (count >= FAILURE_THRESHOLD) {
            circuitState.put(dependency, "OPEN");
            openedAt.put(dependency, Instant.now());
        }
    }

    public void recordDependencySuccess(String dependency) {
        failureCounts.computeIfAbsent(dependency, k -> new AtomicInteger(0)).set(0);
        circuitState.put(dependency, "CLOSED");
        openedAt.remove(dependency);
    }

    public String checkCircuitState(String dependency) {
        String state = circuitState.getOrDefault(dependency, "CLOSED");
        if ("OPEN".equals(state)) {
            Instant tripped = openedAt.get(dependency);
            if (tripped != null && Instant.now().isAfter(tripped.plusSeconds(COOLDOWN_SECONDS))) {
                circuitState.put(dependency, "HALF_OPEN");
                return "HALF_OPEN";
            }
        }
        return state;
    }

    public void forceCooldownForTest(String dependency) {
        openedAt.put(dependency, Instant.now().minusSeconds(COOLDOWN_SECONDS + 5));
    }

    public Map<String, Object> getCircuitBreakerStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        for (String dep : Arrays.asList("FX_RATE_PROVIDER", "PAYMENT_GATEWAY", "BANK_TRANSFER_API")) {
            status.put(dep, checkCircuitState(dep));
        }
        return status;
    }

    private Account findByCode(String code) {
        return accountRepository.findAll().stream()
                .filter(a -> code.equals(a.getAccountCode()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Account code not found: " + code));
    }

    private LedgerEntry buildEntry(String code, String type, BigDecimal amount, String currency, String actor) {
        LedgerEntry e = new LedgerEntry();
        e.setAccount(findByCode(code));
        e.setEntryType(type);
        e.setAmount(amount);
        e.setCurrency(currency);
        e.setCreatedBy(actor);
        return e;
    }
}