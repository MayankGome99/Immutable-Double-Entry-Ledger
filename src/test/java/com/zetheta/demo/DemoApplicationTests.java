package com.zetheta.demo;

import com.zetheta.demo.models.Account;
import com.zetheta.demo.models.LedgerEntry;
import com.zetheta.demo.repositories.AccountRepository;
import com.zetheta.demo.repositories.LedgerEntryRepository;
import com.zetheta.demo.services.LedgerService;
import com.zetheta.demo.utils.HashUtils;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;



@SpringBootTest
class DemoApplicationTests {

	@Autowired
	private LedgerService ledgerService;

	@Autowired
	private AccountRepository accountRepository;

	@Autowired
	private LedgerEntryRepository entryRepository;

	private Account getAccountByCode(String code) {
		return accountRepository.findAll().stream()
				.filter(a -> code.equals(a.getAccountCode()))
				.findFirst()
				.orElseThrow(() -> new AssertionError("Seeded account not found: " + code));
	}

	@Test
	void testSha256HashIsDeterministicAndTamperSensitive() {
		String h1 = HashUtils.generateSHA256("J1DEBIT100GENESIS_HASH");
		String h2 = HashUtils.generateSHA256("J1DEBIT100GENESIS_HASH");
		String h3 = HashUtils.generateSHA256("J1DEBIT101GENESIS_HASH");

		assertEquals(64, h1.length());
		assertEquals(h1, h2);
		assertNotEquals(h1, h3);
	}

	@Test
	void testUnbalancedTransactionIsRejected() {
		Account wallet = getAccountByCode("1001");
		Account liability = getAccountByCode("2001");

		LedgerEntry debit = new LedgerEntry();
		debit.setAccount(wallet);
		debit.setEntryType("DEBIT");
		debit.setAmount(new BigDecimal("500.00"));
		debit.setCurrency("INR");
		debit.setCreatedBy("TEST");

		LedgerEntry credit = new LedgerEntry();
		credit.setAccount(liability);
		credit.setEntryType("CREDIT");
		credit.setAmount(new BigDecimal("499.00"));
		credit.setCurrency("INR");
		credit.setCreatedBy("TEST");

		assertThrows(IllegalArgumentException.class,
				() -> ledgerService.postTransaction(Arrays.asList(debit, credit)));
	}

	@Test
	void testBalancedDepositAndBalanceSheetEquation() {
		Account wallet = getAccountByCode("1001");
		Account liability = getAccountByCode("2001");

		BigDecimal before = entryRepository.calculateBalance(wallet.getId());

		LedgerEntry debit = new LedgerEntry();
		debit.setAccount(wallet);
		debit.setEntryType("DEBIT");
		debit.setAmount(new BigDecimal("250.00"));
		debit.setCurrency("INR");
		debit.setCreatedBy("TEST");

		LedgerEntry credit = new LedgerEntry();
		credit.setAccount(liability);
		credit.setEntryType("CREDIT");
		credit.setAmount(new BigDecimal("250.00"));
		credit.setCurrency("INR");
		credit.setCreatedBy("TEST");

		UUID journalId = ledgerService.postTransaction(Arrays.asList(debit, credit));
		assertNotNull(journalId);

		BigDecimal after = entryRepository.calculateBalance(wallet.getId());
		assertEquals(0, after.subtract(before).compareTo(new BigDecimal("250.00")));

		Map<String, Object> balanceSheet = ledgerService.getBalanceSheet();
		assertEquals(true, balanceSheet.get("accountingEquationBalanced"));
	}

	@Test
	void testDuplicateIdempotencyKeyIsRejected() {
		Account wallet = getAccountByCode("1001");
		Account liability = getAccountByCode("2001");
		String fixedKey = "IDEM-TEST-" + UUID.randomUUID();

		LedgerEntry d1 = new LedgerEntry();
		d1.setAccount(wallet);
		d1.setEntryType("DEBIT");
		d1.setAmount(new BigDecimal("100.00"));
		d1.setCurrency("INR");
		d1.setCreatedBy("TEST");
		d1.setIdempotencyKey(fixedKey + "-0");

		LedgerEntry c1 = new LedgerEntry();
		c1.setAccount(liability);
		c1.setEntryType("CREDIT");
		c1.setAmount(new BigDecimal("100.00"));
		c1.setCurrency("INR");
		c1.setCreatedBy("TEST");
		c1.setIdempotencyKey(fixedKey + "-1");

		ledgerService.postTransaction(Arrays.asList(d1, c1));

		LedgerEntry d2 = new LedgerEntry();
		d2.setAccount(wallet);
		d2.setEntryType("DEBIT");
		d2.setAmount(new BigDecimal("100.00"));
		d2.setCurrency("INR");
		d2.setCreatedBy("TEST");
		d2.setIdempotencyKey(fixedKey + "-0");

		LedgerEntry c2 = new LedgerEntry();
		c2.setAccount(liability);
		c2.setEntryType("CREDIT");
		c2.setAmount(new BigDecimal("100.00"));
		c2.setCurrency("INR");
		c2.setCreatedBy("TEST");
		c2.setIdempotencyKey(fixedKey + "-1");

		assertThrows(IllegalStateException.class,
				() -> ledgerService.postTransaction(Arrays.asList(d2, c2)));
	}

	@Test
	void testReversalMirrorsEntriesAndBlocksDoubleReversal() {
		Account wallet = getAccountByCode("1001");
		Account liability = getAccountByCode("2001");

		LedgerEntry d = new LedgerEntry();
		d.setAccount(wallet);
		d.setEntryType("DEBIT");
		d.setAmount(new BigDecimal("75.00"));
		d.setCurrency("INR");
		d.setCreatedBy("TEST");

		LedgerEntry c = new LedgerEntry();
		c.setAccount(liability);
		c.setEntryType("CREDIT");
		c.setAmount(new BigDecimal("75.00"));
		c.setCurrency("INR");
		c.setCreatedBy("TEST");

		UUID originalJournalId = ledgerService.postTransaction(Arrays.asList(d, c));
		UUID reversalJournalId = ledgerService.revertTransaction(originalJournalId);
		assertNotNull(reversalJournalId);

		assertThrows(IllegalStateException.class,
				() -> ledgerService.revertTransaction(originalJournalId));
	}

	@Test
	void testConcurrentWithdrawalsPreventDoubleSpend() throws InterruptedException {
		Account wallet = getAccountByCode("1001");
		Account liability = getAccountByCode("2001");

		// Ensure wallet has positive funds
		LedgerEntry seedD = new LedgerEntry();
		seedD.setAccount(wallet);
		seedD.setEntryType("DEBIT");
		seedD.setAmount(new BigDecimal("1000.00"));
		seedD.setCurrency("INR");
		seedD.setCreatedBy("TEST");

		LedgerEntry seedC = new LedgerEntry();
		seedC.setAccount(liability);
		seedC.setEntryType("CREDIT");
		seedC.setAmount(new BigDecimal("1000.00"));
		seedC.setCurrency("INR");
		seedC.setCreatedBy("TEST");
		ledgerService.postTransaction(Arrays.asList(seedD, seedC));

		BigDecimal fullBalance = entryRepository.calculateBalance(wallet.getId());
		int threads = 5;
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		CountDownLatch startGate = new CountDownLatch(1);
		CountDownLatch doneGate = new CountDownLatch(threads);
		AtomicInteger successCount = new AtomicInteger(0);
		AtomicInteger failureCount = new AtomicInteger(0);

		for (int i = 0; i < threads; i++) {
			pool.submit(() -> {
				try {
					startGate.await();
					LedgerEntry wCredit = new LedgerEntry();
					wCredit.setAccount(wallet);
					wCredit.setEntryType("CREDIT");
					wCredit.setAmount(fullBalance);
					wCredit.setCurrency("INR");
					wCredit.setCreatedBy("CONCURRENCY_TEST");

					LedgerEntry lDebit = new LedgerEntry();
					lDebit.setAccount(liability);
					lDebit.setEntryType("DEBIT");
					lDebit.setAmount(fullBalance);
					lDebit.setCurrency("INR");
					lDebit.setCreatedBy("CONCURRENCY_TEST");

					ledgerService.postTransaction(Arrays.asList(wCredit, lDebit));
					successCount.incrementAndGet();
				} catch (Exception ex) {
					failureCount.incrementAndGet();
				} finally {
					doneGate.countDown();
				}
			});
		}

		startGate.countDown();
		doneGate.await(15, TimeUnit.SECONDS);
		pool.shutdown();

		assertEquals(1, successCount.get(), "Exactly 1 concurrent full-balance withdrawal must succeed");
		assertEquals(threads - 1, failureCount.get(), "All other concurrent withdrawals must fail");
		assertTrue(entryRepository.calculateBalance(wallet.getId()).compareTo(BigDecimal.ZERO) >= 0);

		assertTrue(ledgerService.verifyAuditTrail().startsWith("AUDIT PASSED"));


	}
	static {
		TimeZone.setDefault(TimeZone.getTimeZone("Asia/Kolkata"));
	}
}