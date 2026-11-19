package com.zetheta.demo.controllers;

import com.zetheta.demo.Data_Transfer_Object.TransferRequest;
import com.zetheta.demo.models.Account;
import com.zetheta.demo.models.LedgerEntry;
import com.zetheta.demo.repositories.AccountRepository;
import com.zetheta.demo.repositories.LedgerEntryRepository;
import com.zetheta.demo.services.LedgerService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.ZonedDateTime;
import java.util.*;

@RestController
@RequestMapping("/api/ledger")
public class LedgerController {

    private final LedgerService ledgerService;
    private final AccountRepository accountRepository;
    private final LedgerEntryRepository entryRepository;

    public LedgerController(LedgerService ledgerService,
                            AccountRepository accountRepository,
                            LedgerEntryRepository entryRepository) {
        this.ledgerService = ledgerService;
        this.accountRepository = accountRepository;
        this.entryRepository = entryRepository;
    }

    @GetMapping("/health")
    public ResponseEntity<Object> healthCheck() {
        Map<String, Object> health = new LinkedHashMap<>();
        health.put("status", "UP");
        health.put("database", "CONNECTED");
        health.put("accountsCount", accountRepository.count());
        health.put("ledgerEntriesCount", entryRepository.count());
        health.put("auditStatus", ledgerService.verifyAuditTrail());
        health.put("timestamp", ZonedDateTime.now().toString());
        return ResponseEntity.ok(health);
    }

    @GetMapping("/accounts")
    public List<Account> getAllAccounts() {
        return accountRepository.findAll();
    }

    @GetMapping("/entries")
    public List<LedgerEntry> getAllEntries() {
        return entryRepository.findAll();
    }

    @PostMapping("/transactions")
    public ResponseEntity<Object> createTransaction(
            @RequestHeader(value = "X-Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody List<LedgerEntry> entries) {
        for (int i = 0; i < entries.size(); i++) {
            entries.get(i).setEntryId(null);
            if (idempotencyKey != null && !idempotencyKey.isBlank()) {
                entries.get(i).setIdempotencyKey(idempotencyKey + "-" + i);
            }
        }
        UUID journalId = ledgerService.postTransaction(entries);
        return ResponseEntity.ok(Map.of(
                "message", "Transaction posted and locked with SHA-256 successfully!",
                "journalId", journalId.toString()
        ));
    }

    @PostMapping("/reversals/{journalId}")
    public ResponseEntity<Object> reverseTransaction(@PathVariable UUID journalId) {
        UUID reversalJournalId = ledgerService.revertTransaction(journalId);
        return ResponseEntity.ok(Map.of(
                "message", "Reversal posted successfully following no-mutation principle.",
                "originalJournalId", journalId.toString(),
                "reversalJournalId", reversalJournalId.toString()
        ));
    }

    @GetMapping("/trial-balance")
    public List<Object[]> getTrialBalance() {
        return entryRepository.generateTrialBalance();
    }

    @GetMapping("/reports/income-statement")
    public ResponseEntity<Object> getIncomeStatement() {
        return ResponseEntity.ok(ledgerService.getIncomeStatement());
    }

    @GetMapping("/reports/balance-sheet")
    public ResponseEntity<Object> getBalanceSheet() {
        return ResponseEntity.ok(ledgerService.getBalanceSheet());
    }

    @GetMapping("/audit")
    public ResponseEntity<String> runSecurityAudit() {
        return ResponseEntity.ok(ledgerService.verifyAuditTrail());
    }

    @GetMapping("/accounts/{accountId}/statement")
    public ResponseEntity<Object> getAccountStatement(@PathVariable UUID accountId) {
        List<LedgerEntry> statement = entryRepository.findByAccount_IdOrderByEffectiveDateDesc(accountId);
        if (statement.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(statement);
    }

    @GetMapping(value = "/accounts/{accountId}/statement/csv", produces = "text/csv")
    public ResponseEntity<String> exportAccountStatementCsv(@PathVariable UUID accountId) {
        List<LedgerEntry> statement = entryRepository.findByAccount_IdOrderByEffectiveDateDesc(accountId);
        StringBuilder csv = new StringBuilder("entry_id,journal_id,entry_type,amount,currency,status,effective_date,created_by\n");
        for (LedgerEntry e : statement) {
            csv.append(e.getEntryId()).append(",")
                    .append(e.getJournalId()).append(",")
                    .append(e.getEntryType()).append(",")
                    .append(e.getAmount()).append(",")
                    .append(e.getCurrency().trim()).append(",")
                    .append(e.getStatus()).append(",")
                    .append(e.getEffectiveDate()).append(",")
                    .append(e.getCreatedBy()).append("\n");
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=statement_" + accountId + ".csv")
                .contentType(MediaType.parseMediaType("text/csv"))
                .body(csv.toString());
    }

    @PostMapping("/transfer")
    public ResponseEntity<Object> transfer(@RequestBody TransferRequest transferRequest) {
        UUID journalId = ledgerService.transferFunds(
                transferRequest.fromAccountId(),
                transferRequest.toAccountId(),
                transferRequest.amount()
        );
        return ResponseEntity.ok(Map.of(
                "message", "Transfer of " + transferRequest.amount() + " completed successfully!",
                "journalId", journalId.toString()
        ));
    }

    @PostMapping("/stress-test")
    public ResponseEntity<String> runStressTest() {
        long startTime = System.currentTimeMillis();

        Account wallet = accountRepository.findAll().stream()
                .filter(a -> "1001".equals(a.getAccountCode()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Wallet 1001 not found"));

        Account depositLiability = accountRepository.findAll().stream()
                .filter(a -> "2001".equals(a.getAccountCode()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Liability 2001 not found"));

        for (int i = 0; i < 1000; i++) {
            LedgerEntry debit = new LedgerEntry();
            debit.setAccount(wallet);
            debit.setEntryType("DEBIT");
            debit.setAmount(new BigDecimal("10.00"));
            debit.setCurrency("INR");
            debit.setCreatedBy("STRESS_TEST");

            LedgerEntry credit = new LedgerEntry();
            credit.setAccount(depositLiability);
            credit.setEntryType("CREDIT");
            credit.setAmount(new BigDecimal("10.00"));
            credit.setCurrency("INR");
            credit.setCreatedBy("STRESS_TEST");

            ledgerService.postTransaction(Arrays.asList(debit, credit));
        }

        long duration = System.currentTimeMillis() - startTime;
        String auditStatus = ledgerService.verifyAuditTrail();

        return ResponseEntity.ok("STRESS TEST PASSED! 1,000 transactions (2,000 ledger entries) posted in "
                + duration + " ms. | " + auditStatus);
    }
}