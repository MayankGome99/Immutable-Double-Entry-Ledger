package com.zetheta.demo.controllers;

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
        try{
            ledgerService.postTransaction(entries);
            return ResponseEntity.ok("Transaction posted and locked with SHA-256 successfully!");

        }catch (IllegalArgumentException e){
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

}
