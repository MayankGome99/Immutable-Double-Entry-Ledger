package com.zetheta.demo;

import com.zetheta.demo.models.Account;
import com.zetheta.demo.models.FeeRefundPolicy;
import com.zetheta.demo.models.LedgerEntry;
import com.zetheta.demo.models.TransactionType;
import com.zetheta.demo.repositories.AccountRepository;
import com.zetheta.demo.services.ExchangeRateService;
import com.zetheta.demo.services.LedgerService;
import com.zetheta.demo.services.TransactionEngineService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class RubricComplianceTests {

    @Autowired
    private TransactionEngineService transactionEngineService;

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
        // Balanced INR opening funding (Total Debits 8,000,000 = Total Credits 8,000,000)
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

        // Balanced USD opening funding (Total Debits 500,000 = Total Credits 500,000)
        ledgerService.postTransaction(Arrays.asList(
                entry("1002", "DEBIT", "500000.00", "USD"),
                entry("3001", "CREDIT", "500000.00", "USD")
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
        UUID orig1 = UUID.randomUUID();
        Map<String, Object> prop = transactionEngineService.processPartialRefund(
                orig1, new BigDecimal("1000.00"), new BigDecimal("20.00"),
                new BigDecimal("500.00"), FeeRefundPolicy.PROPORTIONAL, "idem-" + UUID.randomUUID(), "REFUND_TEST");
        assertEquals(0, new BigDecimal("10.0000").compareTo((BigDecimal) prop.get("feeRefunded")));

        UUID orig2 = UUID.randomUUID();
        Map<String, Object> full = transactionEngineService.processPartialRefund(
                orig2, new BigDecimal("1000.00"), new BigDecimal("20.00"),
                new BigDecimal("500.00"), FeeRefundPolicy.FULL, "idem-" + UUID.randomUUID(), "REFUND_TEST");
        assertEquals(0, new BigDecimal("20.0000").compareTo((BigDecimal) full.get("feeRefunded")));

        UUID orig3 = UUID.randomUUID();
        Map<String, Object> none = transactionEngineService.processPartialRefund(
                orig3, new BigDecimal("1000.00"), new BigDecimal("20.00"),
                new BigDecimal("500.00"), FeeRefundPolicy.NONE, "idem-" + UUID.randomUUID(), "REFUND_TEST");
        assertEquals(0, BigDecimal.ZERO.compareTo((BigDecimal) none.get("feeRefunded")));

        assertTrialBalanceIsZero();
    }

    @Test
    void testOverRefundAndDuplicateIdempotencyBlocked() {
        UUID origId = UUID.randomUUID();
        String idemKey = "dup-key-" + UUID.randomUUID();

        transactionEngineService.processPartialRefund(
                origId, new BigDecimal("1000.00"), new BigDecimal("20.00"),
                new BigDecimal("600.00"), FeeRefundPolicy.NONE, idemKey, "FRAUD_TEST");

        assertThrows(IllegalStateException.class, () ->
                transactionEngineService.processPartialRefund(
                        origId, new BigDecimal("1000.00"), new BigDecimal("20.00"),
                        new BigDecimal("100.00"), FeeRefundPolicy.NONE, idemKey, "FRAUD_TEST"));

        assertThrows(IllegalArgumentException.class, () ->
                transactionEngineService.processPartialRefund(
                        origId, new BigDecimal("1000.00"), new BigDecimal("20.00"),
                        new BigDecimal("500.00"), FeeRefundPolicy.NONE, "new-key-" + UUID.randomUUID(), "FRAUD_TEST"));
    }

    @Test
    void test500RandomizedTransactionsAcrossAll20Types() {
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

    private void assertTrialBalanceIsZero() {
        BigDecimal diff = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(CASE WHEN entry_type = 'DEBIT' THEN amount ELSE 0 END), 0) - " +
                        "COALESCE(SUM(CASE WHEN entry_type = 'CREDIT' THEN amount ELSE 0 END), 0) FROM ledger_entries",
                BigDecimal.class);
        assertNotNull(diff);
        assertEquals(0, BigDecimal.ZERO.compareTo(diff), "Trial balance discrepancy detected: " + diff);
    }
}