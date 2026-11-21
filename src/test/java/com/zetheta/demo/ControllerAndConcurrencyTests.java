package com.zetheta.demo;

import com.zetheta.demo.config.RequestIdFilter;
import com.zetheta.demo.models.Account;
import com.zetheta.demo.models.LedgerEntry;
import com.zetheta.demo.repositories.AccountRepository;
import com.zetheta.demo.repositories.LedgerEntryRepository;
import com.zetheta.demo.services.ExchangeRateService;
import com.zetheta.demo.services.LedgerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
class ControllerAndConcurrencyTests {

    static {
        System.setProperty("user.timezone", "UTC");
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
    }

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private RequestIdFilter requestIdFilter;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private LedgerEntryRepository ledgerEntryRepository;

    @Autowired
    private LedgerService ledgerService;

    @Autowired
    private ExchangeRateService exchangeRateService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private MockMvc mockMvc;

    @BeforeEach
    void setup() {
        this.mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .addFilters(requestIdFilter)
                .build();

        ledgerService.postTransaction(Arrays.asList(
                makeEntry("1001", "DEBIT", "500000.00", "INR"),
                makeEntry("1004", "DEBIT", "500000.00", "INR"),
                makeEntry("2001", "CREDIT", "500000.00", "INR"),
                makeEntry("2002", "CREDIT", "300000.00", "INR"),
                makeEntry("4001", "CREDIT", "200000.00", "INR")
        ));
        ledgerService.postTransaction(Arrays.asList(
                makeEntry("1002", "DEBIT", "10000.00", "USD"),
                makeEntry("3002", "CREDIT", "10000.00", "USD")
        ));
        exchangeRateService.saveRateSnapshot("USD", "INR", new BigDecimal("83.50000000"), "MOCKMVC_TEST", 60);
    }

    @Test
    void testHealthAndRequestIdHeaderAndPartitions() throws Exception {
        mockMvc.perform(get("/api/v1/health").header("X-Request-Id", "req_custom_123"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Request-Id", "req_custom_123"))
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.partitions").isArray())
                .andExpect(jsonPath("$.partitions", not(empty())))
                .andExpect(jsonPath("$.lastTrialBalanceCheckAt", notNullValue()));

        mockMvc.perform(get("/api/ledger/accounts"))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Request-Id"))
                .andExpect(jsonPath("$", not(empty())));

        mockMvc.perform(get("/api/v1/entries"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", not(empty())));
    }

    @Test
    void testTransactionsTransferReversalAndErrorResponses() throws Exception {
        Account wallet = findByCode("1001");
        Account liability = findByCode("2001");
        Account settling = findByCode("1010");
        String idemKey = "mvc-idem-" + UUID.randomUUID();

        String balancedBody = """
                [
                  {"account":{"id":"%s"},"entryType":"DEBIT","amount":125.00,"currency":"INR","createdBy":"MVC_TEST"},
                  {"account":{"id":"%s"},"entryType":"CREDIT","amount":125.00,"currency":"INR","createdBy":"MVC_TEST"}
                ]
                """.formatted(wallet.getId(), liability.getId());

        String postResp = mockMvc.perform(post("/api/v1/transactions")
                        .header("X-Idempotency-Key", idemKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(balancedBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.journalId", notNullValue()))
                .andReturn().getResponse().getContentAsString();

        mockMvc.perform(post("/api/v1/transactions")
                        .header("X-Idempotency-Key", idemKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(balancedBody))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("TXN_409"))
                .andExpect(jsonPath("$.error.request_id", notNullValue()));

        String unbalancedBody = """
                [
                  {"account":{"id":"%s"},"entryType":"DEBIT","amount":100.00,"currency":"INR","createdBy":"MVC_TEST"},
                  {"account":{"id":"%s"},"entryType":"CREDIT","amount":50.00,"currency":"INR","createdBy":"MVC_TEST"}
                ]
                """.formatted(wallet.getId(), liability.getId());

        mockMvc.perform(post("/api/v1/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(unbalancedBody))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.error.code").value("TXN_422"));

        String transferBody = """
                {"fromAccountId":"%s","toAccountId":"%s","amount":50.00}
                """.formatted(wallet.getId(), settling.getId());

        mockMvc.perform(post("/api/v1/transfer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.journalId", notNullValue()));

        String journalId = postResp.replaceAll(".*\"journalId\"\\s*:\\s*\"([^\"]+)\".*", "$1");
        mockMvc.perform(post("/api/v1/reversals/" + journalId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reversalJournalId", notNullValue()));

        mockMvc.perform(post("/api/v1/reversals/" + journalId))
                .andExpect(status().isConflict());
    }

    @Test
    void testReportsStatementsAndCsvWithRunningBalance() throws Exception {
        Account wallet = findByCode("1001");
        String nowIso = ZonedDateTime.now().plusMinutes(5).toString();
        String fromIso = ZonedDateTime.now().minusDays(1).toString();

        mockMvc.perform(get("/api/v1/trial-balance")
                        .param("asOf", nowIso)
                        .param("currency", "INR"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balanced").value(true))
                .andExpect(jsonPath("$.accounts", not(empty())))
                .andExpect(jsonPath("$.currencySummaries[0].currency").value("INR"))
                .andExpect(jsonPath("$.currencySummaries[0].balanced").value(true));

        mockMvc.perform(get("/api/v1/reports/income-statement")
                        .param("from", fromIso)
                        .param("to", nowIso)
                        .param("currency", "INR"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currency").value("INR"))
                .andExpect(jsonPath("$.netIncome", notNullValue()));

        mockMvc.perform(get("/api/v1/reports/balance-sheet")
                        .param("asOf", nowIso)
                        .param("currency", "INR"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountingEquationBalanced").value(true));

        mockMvc.perform(get("/api/v1/accounts/" + wallet.getId() + "/statement")
                        .param("from", fromIso)
                        .param("to", nowIso)
                        .param("page", "0")
                        .param("size", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(5))
                .andExpect(jsonPath("$.entries[0].runningBalance", notNullValue()));

        mockMvc.perform(get("/api/v1/accounts/" + wallet.getId() + "/statement/csv")
                        .param("from", fromIso)
                        .param("to", nowIso))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/csv"))
                .andExpect(content().string(containsString(
                        "entry_id,journal_id,entry_type,amount,currency,status,effective_date,created_by,running_balance")));
    }

    @Test
    void testAdvancedFxRefundAndResilienceEndpoints() throws Exception {
        String typedResp = mockMvc.perform(post("/api/v1/transactions/typed")
                        .param("type", "MERCHANT_PAYMENT_QR")
                        .param("amount", "500.00")
                        .param("createdBy", "MVC_MERCHANT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("POSTED"))
                .andReturn().getResponse().getContentAsString();

        String origJournalId = typedResp.replaceAll(".*\"journalId\"\\s*:\\s*\"([^\"]+)\".*", "$1");

        mockMvc.perform(post("/api/v1/refunds/partial")
                        .header("X-Idempotency-Key", "mvc-ref-" + UUID.randomUUID())
                        .param("originalJournalId", origJournalId)
                        .param("refundAmount", "200.00")
                        .param("feePolicy", "PROPORTIONAL")
                        .param("createdBy", "MVC_REFUND"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PARTIALLY_REFUNDED"));

        mockMvc.perform(post("/api/v1/fx/rates")
                        .param("baseCurrency", "USD")
                        .param("quoteCurrency", "INR")
                        .param("rate", "84.10000000")
                        .param("source", "MVC_FX")
                        .param("validMinutes", "60"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.baseCurrency").value("USD"));

        mockMvc.perform(post("/api/v1/fx/convert/usd-to-inr")
                        .param("usdAmount", "50.00")
                        .param("createdBy", "MVC_FX"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.usdJournalId", notNullValue()));

        mockMvc.perform(get("/api/v1/reports/currency-exposure"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.report").value("Currency Exposure Report"));

        mockMvc.perform(post("/api/v1/fx/revalue"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.job").value("UNREALISED_FX_REVALUATION"));

        mockMvc.perform(get("/api/v1/audit/verify"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("AUDIT PASSED")));

        mockMvc.perform(get("/api/v1/audit/anomalies").param("threshold", "10000.00"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.report").value("Audit Anomaly Detection"));

        mockMvc.perform(get("/api/v1/circuit-breaker/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.FX_RATE_PROVIDER", notNullValue()));
    }

    @Test
    void test10ConcurrentReversalsExactlyOneSucceeds() throws InterruptedException {
        UUID originalJournalId = ledgerService.postTransaction(Arrays.asList(
                makeEntry("1001", "DEBIT", "450.00", "INR"),
                makeEntry("2001", "CREDIT", "450.00", "INR")
        ));

        int threads = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger succeeded = new AtomicInteger(0);
        AtomicInteger failed = new AtomicInteger(0);

        for (int i = 0; i < threads; i++) {
            executor.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    ledgerService.revertTransaction(originalJournalId);
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

        assertEquals(1, succeeded.get(), "Exactly 1 concurrent reversal must succeed");
        assertEquals(9, failed.get(), "Remaining 9 concurrent reversals must be rejected");

        BigDecimal diff = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(CASE WHEN entry_type = 'DEBIT' THEN amount ELSE 0 END), 0) - " +
                        "COALESCE(SUM(CASE WHEN entry_type = 'CREDIT' THEN amount ELSE 0 END), 0) FROM ledger_entries",
                BigDecimal.class);
        assertEquals(0, BigDecimal.ZERO.compareTo(diff));
    }

    @Test
    void test20ConcurrentBidirectionalP2PTransfersDeadlockFree() throws Exception {
        Account walletA = findByCode("1001");
        Account walletB = findByCode("1004");

        BigDecimal startA = ledgerEntryRepository.calculateBalanceByCurrency(walletA.getId(), "INR");
        BigDecimal startB = ledgerEntryRepository.calculateBalanceByCurrency(walletB.getId(), "INR");
        BigDecimal combinedBefore = startA.add(startB);

        int threads = 20;
        BigDecimal transferAmt = new BigDecimal("10.0000");
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<UUID>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            final boolean aToB = (i % 2 == 0);
            futures.add(pool.submit(() -> {
                ready.countDown();
                start.await();
                return aToB
                        ? ledgerService.transferFunds(walletA.getId(), walletB.getId(), transferAmt)
                        : ledgerService.transferFunds(walletB.getId(), walletA.getId(), transferAmt);
            }));
        }

        assertTrue(ready.await(10, TimeUnit.SECONDS));
        start.countDown();

        int succeeded = 0;
        for (Future<UUID> f : futures) {
            assertNotNull(f.get(30, TimeUnit.SECONDS));
            succeeded++;
        }
        pool.shutdown();

        BigDecimal endA = ledgerEntryRepository.calculateBalanceByCurrency(walletA.getId(), "INR");
        BigDecimal endB = ledgerEntryRepository.calculateBalanceByCurrency(walletB.getId(), "INR");

        assertEquals(20, succeeded, "All 20 concurrent P2P transfers must succeed without deadlock");
        assertEquals(0, combinedBefore.compareTo(endA.add(endB)), "Total value across wallets must be conserved");
        assertEquals(0, startA.compareTo(endA), "Equal bi-directional transfers must preserve walletA balance");
        assertEquals(0, startB.compareTo(endB), "Equal bi-directional transfers must preserve walletB balance");
        assertEquals(true, ledgerService.getTrialBalance(null, "INR").get("balanced"));
    }

    private Account findByCode(String code) {
        return accountRepository.findAll().stream()
                .filter(a -> code.equals(a.getAccountCode()))
                .findFirst()
                .orElseThrow();
    }

    private LedgerEntry makeEntry(String code, String type, String amount, String currency) {
        LedgerEntry e = new LedgerEntry();
        e.setAccount(findByCode(code));
        e.setEntryType(type);
        e.setAmount(new BigDecimal(amount));
        e.setCurrency(currency);
        e.setCreatedBy("MVC_SEED");
        return e;
    }
}