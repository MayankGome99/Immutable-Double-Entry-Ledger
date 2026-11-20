


package com.zetheta.demo;

import com.zetheta.demo.models.Account;
import com.zetheta.demo.models.LedgerEntry;
import com.zetheta.demo.repositories.AccountRepository;
import com.zetheta.demo.repositories.LedgerEntryRepository;
import com.zetheta.demo.services.ExchangeRateService;
import com.zetheta.demo.services.LedgerService;
import com.zetheta.demo.services.ResilienceAndAuditService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class ResilienceAndLoadTests {

    static {
        System.setProperty("user.timezone", "UTC");
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"));
    }

    @Autowired
    private ResilienceAndAuditService resilienceService;

    @Autowired
    private ExchangeRateService exchangeRateService;

    @Autowired
    private LedgerService ledgerService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private LedgerEntryRepository entryRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void testUnrealisedFxRevaluationProducesBalancedEntries() {
        Account usdWallet = findByCode("1002");
        Account equity = findByCode("3002");
        ledgerService.postTransaction(Arrays.asList(
                makeEntry(usdWallet, "DEBIT", new BigDecimal("1000.00"), "USD"),
                makeEntry(equity, "CREDIT", new BigDecimal("1000.00"), "USD")
        ));

        exchangeRateService.saveRateSnapshot("USD", "INR", new BigDecimal("84.00000000"), "CLOSING_RATE", 60);
        Map<String, Object> result = resilienceService.runUnrealisedFxRevaluation(new BigDecimal("83.00000000"));

        assertEquals("UNREALISED_GAIN", result.get("pnlType"));
        assertNotEquals("NO_MOVEMENT", result.get("journalId"));
        assertTrialBalanceZero();
    }

    @Test
    void testAuditAnomalyDetectionFlagsLargeRoundNumbers() {
        Account wallet = findByCode("1001");
        Account deposit = findByCode("2001");
        ledgerService.postTransaction(Arrays.asList(
                makeEntry(wallet, "DEBIT", new BigDecimal("50000.00"), "INR"),
                makeEntry(deposit, "CREDIT", new BigDecimal("50000.00"), "INR")
        ));

        Map<String, Object> report = resilienceService.detectAnomalies(new BigDecimal("10000.00"));
        assertTrue(((Integer) report.get("anomaliesDetected")) > 0);
    }

    @Test
    void testCircuitBreakerLifecycleTransitions() {
        String dep = "FX_RATE_PROVIDER";
        resilienceService.recordDependencySuccess(dep);
        assertEquals("CLOSED", resilienceService.checkCircuitState(dep));

        for (int i = 0; i < 5; i++) {
            resilienceService.recordDependencyFailure(dep);
        }
        assertEquals("OPEN", resilienceService.checkCircuitState(dep));

        resilienceService.forceCooldownForTest(dep);
        assertEquals("HALF_OPEN", resilienceService.checkCircuitState(dep));

        resilienceService.recordDependencySuccess(dep);
        assertEquals("CLOSED", resilienceService.checkCircuitState(dep));
    }

    @Test
    void test50ConcurrentWithdrawalsPreventDoubleSpend() throws InterruptedException {
        UUID testWalletId = UUID.randomUUID();
        String code = "T" + (System.currentTimeMillis() % 1000000);
        jdbcTemplate.update(
                "INSERT INTO accounts (id, account_code, account_name, account_type, sub_type, currency) " +
                        "VALUES (?, ?, 'Concurrent Load Test Wallet', 'Asset', 'Current Asset', 'INR')",
                testWalletId, code);

        Account testWallet = accountRepository.findById(testWalletId).orElseThrow();
        Account depositLiability = findByCode("2001");

        // Fund exact INR 10,000.00
        ledgerService.postTransaction(Arrays.asList(
                makeEntry(testWallet, "DEBIT", new BigDecimal("10000.00"), "INR"),
                makeEntry(depositLiability, "CREDIT", new BigDecimal("10000.00"), "INR")
        ));

        int totalRequests = 50;
        ExecutorService executor = Executors.newFixedThreadPool(16);
        CountDownLatch latch = new CountDownLatch(totalRequests);
        AtomicInteger succeeded = new AtomicInteger(0);
        AtomicInteger failed = new AtomicInteger(0);

        for (int i = 0; i < totalRequests; i++) {
            executor.submit(() -> {
                try {
                    resilienceService.withdrawWithAdvisoryLock(testWalletId, new BigDecimal("500.00"), "LOAD_50");
                    succeeded.incrementAndGet();
                } catch (Exception ex) {
                    failed.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(30, TimeUnit.SECONDS));
        executor.shutdown();

        // Day 7 Acceptance Criteria: 50 x INR 500 on INR 10,000 => exactly 20 succeed, 30 fail, balance = 0
        assertEquals(20, succeeded.get(), "Exactly 20 withdrawals should succeed");
        assertEquals(30, failed.get(), "Exactly 30 withdrawals should fail with insufficient balance");
        assertEquals(0, BigDecimal.ZERO.compareTo(entryRepository.calculateBalance(testWalletId)));
        assertTrialBalanceZero();
    }

    private Account findByCode(String code) {
        return accountRepository.findAll().stream()
                .filter(a -> code.equals(a.getAccountCode()))
                .findFirst()
                .orElseThrow();
    }

    private LedgerEntry makeEntry(Account acc, String type, BigDecimal amt, String curr) {
        LedgerEntry e = new LedgerEntry();
        e.setAccount(acc);
        e.setEntryType(type);
        e.setAmount(amt);
        e.setCurrency(curr);
        e.setCreatedBy("RESILIENCE_TEST");
        return e;
    }

    private void assertTrialBalanceZero() {
        BigDecimal diff = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(CASE WHEN entry_type = 'DEBIT' THEN amount ELSE 0 END), 0) - " +
                        "COALESCE(SUM(CASE WHEN entry_type = 'CREDIT' THEN amount ELSE 0 END), 0) FROM ledger_entries",
                BigDecimal.class);
        assertEquals(0, BigDecimal.ZERO.compareTo(diff));
    }
}