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
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class DemoApplicationTests {

	static {
		System.setProperty("user.timezone", "UTC");
		TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
	}

	@Autowired
	private LedgerService ledgerService;

	@Autowired
	private AccountRepository accountRepository;

	@Autowired
	private LedgerEntryRepository entryRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

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
	void testCurrencyMismatchIsRejected() {
		Account inrWallet = getAccountByCode("1001");
		Account inrLiability = getAccountByCode("2001");

		LedgerEntry debit = new LedgerEntry();
		debit.setAccount(inrWallet);
		debit.setEntryType("DEBIT");
		debit.setAmount(new BigDecimal("100.00"));
		debit.setCurrency("USD"); // Wrong currency for INR account 1001
		debit.setCreatedBy("TEST");

		LedgerEntry credit = new LedgerEntry();
		credit.setAccount(inrLiability);
		credit.setEntryType("CREDIT");
		credit.setAmount(new BigDecimal("100.00"));
		credit.setCurrency("USD");
		credit.setCreatedBy("TEST");

		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				() -> ledgerService.postTransaction(Arrays.asList(debit, credit)));
		assertTrue(ex.getMessage().contains("Currency mismatch"));
	}

	@Test
	void testImmutabilityTriggerBlocksDelete() {
		Account wallet = getAccountByCode("1001");
		Account liability = getAccountByCode("2001");

		LedgerEntry debit = new LedgerEntry();
		debit.setAccount(wallet);
		debit.setEntryType("DEBIT");
		debit.setAmount(new BigDecimal("75.00"));
		debit.setCurrency("INR");
		debit.setCreatedBy("TEST_DELETE");

		LedgerEntry credit = new LedgerEntry();
		credit.setAccount(liability);
		credit.setEntryType("CREDIT");
		credit.setAmount(new BigDecimal("75.00"));
		credit.setCurrency("INR");
		credit.setCreatedBy("TEST_DELETE");

		UUID journalId = ledgerService.postTransaction(Arrays.asList(debit, credit));
		UUID entryId = jdbcTemplate.queryForObject(
				"SELECT entry_id FROM ledger_entries WHERE journal_id = ? LIMIT 1",
				UUID.class, journalId);

		UncategorizedSQLException ex = assertThrows(
				UncategorizedSQLException.class,
				() -> jdbcTemplate.update("DELETE FROM ledger_entries WHERE entry_id = ?", entryId)
		);
		assertTrue(ex.getMessage().contains("Immutable ledger"));
	}

	@Test
	@Transactional
	void testAuditTrailDetectsActualDatabaseTampering() {
		Account wallet = getAccountByCode("1001");
		Account liability = getAccountByCode("2001");

		LedgerEntry debit = new LedgerEntry();
		debit.setAccount(wallet);
		debit.setEntryType("DEBIT");
		debit.setAmount(new BigDecimal("150.00"));
		debit.setCurrency("INR");
		debit.setCreatedBy("TEST_TAMPER");

		LedgerEntry credit = new LedgerEntry();
		credit.setAccount(liability);
		credit.setEntryType("CREDIT");
		credit.setAmount(new BigDecimal("150.00"));
		credit.setCurrency("INR");
		credit.setCreatedBy("TEST_TAMPER");

		UUID journalId = ledgerService.postTransaction(Arrays.asList(debit, credit));
		UUID entryId = jdbcTemplate.queryForObject(
				"SELECT entry_id FROM ledger_entries WHERE journal_id = ? LIMIT 1",
				UUID.class, journalId);

		// Simulate a rogue DBA bypassing the trigger and corrupting the amount
		jdbcTemplate.execute("ALTER TABLE ledger_entries DISABLE TRIGGER enforce_ledger_immutability");
		try {
			jdbcTemplate.update("UPDATE ledger_entries SET amount = amount + 1 WHERE entry_id = ?", entryId);
			String auditResult = ledgerService.verifyAuditTrail();
			assertTrue(auditResult.contains("TAMPERING DETECTED"),
					"Expected tampering alert but got: " + auditResult);
		} finally {
			jdbcTemplate.execute("ALTER TABLE ledger_entries ENABLE TRIGGER enforce_ledger_immutability");
		}
		// @Transactional automatically rolls back the tampered row so the DB stays clean
	}
}