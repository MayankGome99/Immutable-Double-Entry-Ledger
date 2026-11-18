package com.zetheta.demo.controllers;

import com.zetheta.demo.Data_Transfer_Object.TransferRequest;
import com.zetheta.demo.models.Account;
import com.zetheta.demo.models.LedgerEntry;
import com.zetheta.demo.repositories.AccountRepository;
import com.zetheta.demo.repositories.LedgerEntryRepository;
import com.zetheta.demo.services.LedgerService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

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

    //1 GET Request: View all  seeded accounts in the browser
    @GetMapping("/accounts")
    public List<Account> getAllAccounts() {
        return accountRepository.findAll();
    }

    // 2 GET Request: View all posted ledger entries

    @GetMapping("/entries")
    public List<LedgerEntry> getAllEntries() {
        return entryRepository.findAll();
    }

    // 3. POST Request: Send a new transaction (Debit + Credit) to the browser or database kinda like brain

    @PutMapping("/transactions")
    public ResponseEntity<String> createTransaction(@RequestBody List<LedgerEntry> entries) {
        for (LedgerEntry entry : entries) {
            entry.setEntryId(null);
        }
            ledgerService.postTransaction(entries);
            return ResponseEntity.ok("Transaction posted and locked with SHA-256 successfully!");


        }

        //4. GET Request: View the Trial Balance
        @GetMapping("/trial-balance")
         public List<Object[]> getTrialBalance() {
        return entryRepository.generateTrialBalance();
        }

       // 5. GET Request: Run the Security Audit

    @GetMapping("/audit")
    public ResponseEntity<String> runSecurityAudit(){
        String auditResult = ledgerService.verifyAuditTrail();
        return ResponseEntity.ok(auditResult);


    }

    //   6. GET Request: View Customer Passbook

    @GetMapping("/accounts/{accountId}/statement")
    public ResponseEntity<List<LedgerEntry>> getAccountStatement(@PathVariable java.util.UUID accountId){
        List<LedgerEntry> statement = entryRepository.findByAccount_IdOrderByEffectiveDateDesc(accountId);
        if(statement.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(statement);
    }

    //7. POST Request: Send P2P Transfer

        @PostMapping("/transfer")
    public org.springframework.http.ResponseEntity<String> transfer(@RequestBody com.zetheta.demo.Data_Transfer_Object.TransferRequest transferRequest){
        ledgerService.transferFunds(transferRequest.fromAccountId(), transferRequest.toAccountId(), transferRequest.amount());
        return org.springframework.http.ResponseEntity.ok("Transfer of " + transferRequest.amount() + " completed successfully!");

        }
}

