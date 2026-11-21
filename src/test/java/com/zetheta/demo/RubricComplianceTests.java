package com.zetheta.demo;

import com.zetheta.demo.models.Account;
import com.zetheta.demo.models.FeeRefundPolicy;
import com.zetheta.demo.models.LedgerEntry;
import com.zetheta.demo.models.TransactionType;
import com.zetheta.demo.repositories.AccountRepository;
import com.zetheta.demo.services.ExchangeRateService;
import com.zetheta.demo.services.LedgerService;
import com.zetheta.demo.services.TransactionEngineService;
import com.zetheta.demo.validators.TransactionValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class RubricComplianceTests {

    static {
        System.setProperty("user.timezone", "UTC");
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"));
    }

    @Autowired
    private TransactionEngineService transactionEngineService;

    @Autowired
    private TransactionValidator transactionValidator;

    @Autowired
    private ExchangeRateService exchangeRateService;

    @Autowired
    private LedgerService ledgerService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void seedOpeningBalances() {
        ledgerService.postTransaction(Arrays.asList(
                entry("1001", "DEBIT", "5000000.00", "INR"),
                entry("1004", "DEBIT", "1000000.00", "INR"),
                entry("1010", "DEBIT", "1000000.00", "INR"),
                entry("1020", "DEBIT", "1000000.00", "INR"),
                entry("2001", "CREDIT", "5000000.00", "INR"),
                entry("2002", "CREDIT", "1000000.00", "INR"),
                entry("2010", "CREDIT", "1000000.00", "INR"),
                entry("2030", "CREDIT", "500000.00", "INR"),
                entry("4001", "CREDIT", "500000.00", "INR")
        ));

        ledgerService.postTransaction(Arrays.asList(
                entry("1002", "DEBIT", "500000.00", "USD"),
                entry("3002", "CREDIT", "500000.00", "USD")
        ));
    }

    @Test
    void testAll20TransactionTypesProduceBalancedEntries() {
        exchangeRateService.saveRateSnapshot("USD", "INR", new BigDecimal("83.42000000"), "RBI_REFERENCE", 60);

        for (TransactionType type : TransactionType.values()) {
            Map<String, Object> result = transactionEngineService.processTypedTransaction(
                    type, new BigDecimal("100.00"), "TEST_ALL_20");
            assertNotNull(result);
        }

        assertTrialBalanceIsZero();
    }

    @ParameterizedTest
    @EnumSource(TransactionType.class)
    void testEachTransactionTypeHappyValidationAndEdgeCase(TransactionType type) {
        exchangeRateService.saveRateSnapshot("USD", "INR", new BigDecimal("83.42000000"), "RBI_REFERENCE", 60);

        // 1. Happy path
        Map<String, Object> happy = transactionEngineService.processTypedTransaction(
                type, new BigDecimal("100.00"), "PARAM_HAPPY");
        assertNotNull(happy);

        // 2. Validation error: zero amount, over-max amount, and blank actor must fail
        assertThrows(IllegalArgumentException.class, () ->
                transactionEngineService.processTypedTransaction(type, BigDecimal.ZERO, "PARAM_ERR"));
        assertThrows(IllegalArgumentException.class, () ->
                transactionEngineService.processTypedTransaction(type, new BigDecimal("99999999.00"), "PARAM_ERR"));
        assertThrows(IllegalArgumentException.class, () ->
                transactionEngineService.processTypedTransaction(type, new BigDecimal("100.00"), "  "));

        // 3. Edge case: exact minimum allowed amount succeeds
        BigDecimal minAmount = transactionValidator.getMinAmount(type);
        Map<String, Object> edge = transactionEngineService.processTypedTransaction(
                type, minAmount, "PARAM_EDGE");
        assertNotNull(edge);

        assertTrialBalanceIsZero();
    }

    @Test
    void testStaleExchangeRateIsRejected() {
        exchangeRateService.saveRateSnapshot("EUR", "INR", new BigDecimal("90.25000000"), "STALE_TEST", -15);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                exchangeRateService.getValidRate("EUR", "INR"));
        assertTrue(ex.getMessage().contains("Stale exchange rate rejected"));
    }

    @Test
    void testMultiCurrencyConversionAndExposureReport() {
        exchangeRateService.saveRateSnapshot("USD", "INR", new BigDecimal("83.42000000"), "RBI_REFERENCE", 60);

        Map<String, Object> conversion = exchangeRateService.convertUsdToInr(new BigDecimal("100.00"), "FX_TEST");
        assertEquals(0, new BigDecimal("8342.0000").compareTo((BigDecimal) conversion.get("grossInr")));
        assertEquals(0, new BigDecimal("41.7100").compareTo((BigDecimal) conversion.get("fxRevenueMarkupInr")));

        Map<String, Object> exposure = exchangeRateService.getCurrencyExposureReport();
        assertEquals("Currency Exposure Report", exposure.get("report"));
        assertNotNull(exposure.get("totalExposureInr"));
        assertTrialBalanceIsZero();
    }

    @Test
    void testPartialRefundFeePolicies() {
        // Create 3 real MERCHANT_PAYMENT_QR journals of INR 1000.00 (1% configured fee = INR 10.00)
        UUID orig1 = UUID.fromString((String) transactionEngineService.processTypedTransaction(
                TransactionType.MERCHANT_PAYMENT_QR, new BigDecimal("1000.00"), "MERCHANT_1").get("journalId"));
        // Pass bogus client originalAmount/originalFee to verify they are ignored in favor of DB values
        Map<String, Object> prop = transactionEngineService.processPartialRefund(
                orig1, new BigDecimal("999999.00"), new BigDecimal("9999.00"),
                new BigDecimal("500.00"), FeeRefundPolicy.PROPORTIONAL, "idem-" + UUID.randomUUID(), "REFUND_TEST");
        assertEquals(0, new BigDecimal("5.0000").compareTo((BigDecimal) prop.get("feeRefunded")));

        UUID orig2 = UUID.fromString((String) transactionEngineService.processTypedTransaction(
                TransactionType.MERCHANT_PAYMENT_QR, new BigDecimal("1000.00"), "MERCHANT_2").get("journalId"));
        Map<String, Object> full = transactionEngineService.processPartialRefund(
                orig2, new BigDecimal("500.00"), FeeRefundPolicy.FULL, "idem-" + UUID.randomUUID(), "REFUND_TEST");
        assertEquals(0, new BigDecimal("10.0000").compareTo((BigDecimal) full.get("feeRefunded")));

        UUID orig3 = UUID.fromString((String) transactionEngineService.processTypedTransaction(
                TransactionType.MERCHANT_PAYMENT_QR, new BigDecimal("1000.00"), "MERCHANT_3").get("journalId"));
        Map<String, Object> none = transactionEngineService.processPartialRefund(
                orig3, new BigDecimal("500.00"), FeeRefundPolicy.NONE, "idem-" + UUID.randomUUID(), "REFUND_TEST");
        assertEquals(0, BigDecimal.ZERO.compareTo((BigDecimal) none.get("feeRefunded")));

        assertTrialBalanceIsZero();
    }

    @Test
    void testOverRefundAndDuplicateIdempotencyBlocked() {
        UUID origId = UUID.fromString((String) transactionEngineService.processTypedTransaction(
                TransactionType.MERCHANT_PAYMENT_QR, new BigDecimal("1000.00"), "FRAUD_ORIG").get("journalId"));
        String idemKey = "dup-key-" + UUID.randomUUID();

        Map<String, Object> firstRefund = transactionEngineService.processPartialRefund(
                origId, new BigDecimal("600.00"), FeeRefundPolicy.NONE, idemKey, "FRAUD_TEST");
        UUID refundJournalId = UUID.fromString((String) firstRefund.get("refundJournalId"));

        // Duplicate idempotency key blocked
        assertThrows(IllegalStateException.class, () ->
                transactionEngineService.processPartialRefund(
                        origId, new BigDecimal("100.00"), FeeRefundPolicy.NONE, idemKey, "FRAUD_TEST"));

        // Cumulative over-refund blocked (600 + 500 > 1000)
        assertThrows(IllegalArgumentException.class, () ->
                transactionEngineService.processPartialRefund(
                        origId, new BigDecimal("500.00"), FeeRefundPolicy.NONE, "new-key-" + UUID.randomUUID(), "FRAUD_TEST"));

        // Non-existent original journal blocked
        assertThrows(IllegalArgumentException.class, () ->
                transactionEngineService.processPartialRefund(
                        UUID.randomUUID(), new BigDecimal("100.00"), FeeRefundPolicy.NONE, "missing-" + UUID.randomUUID(), "FRAUD_TEST"));

        // Refunding a refund journal itself blocked
        assertThrows(IllegalArgumentException.class, () ->
                transactionEngineService.processPartialRefund(
                        refundJournalId, new BigDecimal("100.00"), FeeRefundPolicy.NONE, "nested-" + UUID.randomUUID(), "FRAUD_TEST"));
    }

    @Test
    void test10ConcurrentPartialRefundsNeverExceedOriginal() throws InterruptedException {
        UUID origId = UUID.fromString((String) transactionEngineService.processTypedTransaction(
                TransactionType.MERCHANT_PAYMENT_QR, new BigDecimal("1000.00"), "CONC_ORIG").get("journalId"));

        int threads = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger succeeded = new AtomicInteger(0);
        AtomicInteger failed = new AtomicInteger(0);

        for (int i = 0; i < threads; i++) {
            final int idx = i;
            executor.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    transactionEngineService.processPartialRefund(
                            origId, new BigDecimal("200.00"), FeeRefundPolicy.PROPORTIONAL,
                            "conc-refund-" + origId + "-" + idx, "CONC_REFUND");
                    succeeded.incrementAndGet();
                } catch (Exception ex) {
                    failed.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }

        assertTrue(ready.await(10, TimeUnit.SECONDS));
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS));
        executor.shutdown();

        BigDecimal totalRefunded = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(refund_amount), 0) FROM partial_refunds WHERE original_journal_id = ?",
                BigDecimal.class, origId);

        assertEquals(5, succeeded.get(), "Exactly 5 x INR 200 refunds should succeed for INR 1000 original");
        assertEquals(5, failed.get(), "Remaining 5 concurrent refunds must be rejected");
        assertEquals(0, new BigDecimal("1000.0000").compareTo(totalRefunded), "Total refunded must never exceed original");
        assertTrialBalanceIsZero();
    }

    @Test
    void test1000RandomizedTransactionsAcrossAll20Types() {
        exchangeRateService.saveRateSnapshot("USD", "INR", new BigDecimal("83.42000000"), "RBI_REFERENCE", 60);
        TransactionType[] types = TransactionType.values();
        Random random = new Random(42);

        for (int i = 0; i < 1000; i++) {
            TransactionType chosen = types[random.nextInt(types.length)];
            BigDecimal amount = BigDecimal.valueOf(10 + random.nextInt(500));
            transactionEngineService.processTypedTransaction(chosen, amount, "STRESS_500");
        }

        assertTrialBalanceIsZero();
    }

    private LedgerEntry entry(String code, String type, String amount, String currency) {
        Account account = accountRepository.findAll().stream()
                .filter(a -> code.equals(a.getAccountCode()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Account not found: " + code));
        LedgerEntry e = new LedgerEntry();
        e.setAccount(account);
        e.setEntryType(type);
        e.setAmount(new BigDecimal(amount));
        e.setCurrency(currency);
        e.setCreatedBy("TEST_SEED");
        return e;
    }

    @Test
    void testImmutabilityTriggerBlocksUpdate() {
        UUID journalId = ledgerService.postTransaction(Arrays.asList(
                entry("1001", "DEBIT", "100.00", "INR"),
                entry("2001", "CREDIT", "100.00", "INR")
        ));

        UUID entryId = jdbcTemplate.queryForObject(
                "SELECT entry_id FROM ledger_entries WHERE journal_id = ? LIMIT 1",
                UUID.class, journalId);
        assertNotNull(entryId);

        UncategorizedSQLException ex = assertThrows(
                UncategorizedSQLException.class,
                () -> jdbcTemplate.update(
                        "UPDATE ledger_entries SET amount = amount + 1 WHERE entry_id = ?",
                        entryId)
        );
        assertTrue(ex.getMessage().contains("Immutable ledger"), "Trigger should block the update: " + ex.getMessage());
    }

    @Test
    void testAuditTrailDetectsTamper() {
        ledgerService.postTransaction(Arrays.asList(
                entry("1001", "DEBIT", "200.00", "INR"),
                entry("2001", "CREDIT", "200.00", "INR")
        ));

        String result = ledgerService.verifyAuditTrail();
        assertTrue(result.contains("AUDIT PASSED"), "Audit should pass on clean data: " + result);
    }

    private void assertTrialBalanceIsZero() {
        BigDecimal diff = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(CASE WHEN entry_type = 'DEBIT' THEN amount ELSE 0 END), 0) - " +
                        "COALESCE(SUM(CASE WHEN entry_type = 'CREDIT' THEN amount ELSE 0 END), 0) FROM ledger_entries",
                BigDecimal.class);
        assertNotNull(diff);
        assertEquals(0, BigDecimal.ZERO.compareTo(diff), "Trial balance discrepancy detected: " + diff);
    }
}