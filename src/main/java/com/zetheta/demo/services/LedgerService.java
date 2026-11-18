package com.zetheta.demo.services;

import com.zetheta.demo.models.Account;
import com.zetheta.demo.models.LedgerEntry;
import com.zetheta.demo.repositories.AccountRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.zetheta.demo.repositories.LedgerEntryRepository;
import com.zetheta.demo.utils.HashUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Service
public class LedgerService {

    private final LedgerEntryRepository repository;
    private final AccountRepository accountRepository;

    public LedgerService(LedgerEntryRepository repository, AccountRepository accountRepository) {
        this.repository = repository;
        this.accountRepository = accountRepository;
    }

    @Transactional
    public void postTransaction(List<LedgerEntry> entries) {
        BigDecimal totalDebits = BigDecimal.ZERO;
        BigDecimal totalCredits = BigDecimal.ZERO;

        // for calculating total debits and credits

        for (LedgerEntry entry : entries) {
            if ("DEBIT".equals(entry.getEntryType())) {
                totalDebits = totalDebits.add(entry.getAmount());

            } else if ("CREDIT".equals(entry.getEntryType())) {

                totalCredits = totalCredits.add(entry.getAmount());

            }
        }

        //Debits MUST equal Credits

        if (totalDebits.compareTo(totalCredits) != 0) {
            throw new IllegalArgumentException("Transaction failed: Total Debits (" + totalDebits + ") do not match Total Credits (" + totalCredits + ")");

        }

            // 3. Cryptographic Hash Chaining (Locking the entries)

        String previousHash = "GENESIS_HASH"; // start of the chain
        java.util.UUID generatedJournalId = java.util.UUID.randomUUID(); // Generate one Journal ID for the whole transaction

        java.time.ZonedDateTime now = java.time.ZonedDateTime.now();

        for (LedgerEntry entry : entries) {

            if ("DEBIT".equals(entry.getEntryType())) {
                // Lock the account row in PostgreSQL
                Account account = accountRepository.findByIdWithLock(entry.getAccount().getId())
                        .orElseThrow(() -> new IllegalArgumentException("Account not found"));

                // Calculate real-time balance
                BigDecimal currentBalance = repository.calculateBalance(account.getId());

                // Block if trying to spend more than available

                if (currentBalance.compareTo(entry.getAmount()) < 0) {
                    throw new IllegalArgumentException("Transaction failed: Insufficient balance. Available: " + currentBalance);
                }
            }

            entry.setJournalId(generatedJournalId);
            entry.setEffectiveDate(now);
            entry.setCreatedBy("SYSTEM");

            entry.setIdempotencyKey(java.util.UUID.randomUUID().toString());

            if (entry.getCurrency() == null) {
                entry.setCurrency("INR");
            }

            // Hash the actual transaction data, NOT the database ID!
            String rawData = entry.getJournalId().toString() + entry.getEntryType() + entry.getAmount().toString() + previousHash;

            entry.setHash(HashUtils.generateSHA256(rawData));
            entry.setPreviousHash(previousHash);

            // Agli entry ke liye iska hash 'previous' ban jayega
            previousHash = entry.getHash();
            entry.setStatus("POSTED");


        }

        // Save to database (Hibernate will now safely auto-generate the entryId!)
        repository.saveAll(entries);
    }
    @Transactional
    public void revertTransaction(java.util.UUID originalJournalId){
        List<LedgerEntry> originalEntries = repository.findByJournalId(originalJournalId);

        if(originalEntries.isEmpty()){
            throw new IllegalArgumentException("Transaction failed: Original transaction not found");
        }

        List<LedgerEntry> reversalEntries = new java.util.ArrayList<>();
        for (LedgerEntry  original : originalEntries) {
            LedgerEntry reversal = new LedgerEntry();
            reversal.setAccount(original.getAccount());
            reversal.setAmount(original.getAmount());
            reversal.setCurrency(original.getCurrency());

            // THE SWAP: Debit becomes Credit, Credit becomes Debit
            if ("DEBIT".equals(original.getEntryType())) {
                reversal.setEntryType("CREDIT");

            }else {
                reversal.setEntryType("DEBIT");
            }

            reversalEntries.add(reversal);
        }

        postTransaction(reversalEntries);
    }

   // Cryptographic Audit Verifier

    public String verifyAuditTrail() {
        List<LedgerEntry> allEntries = repository.findAll();
        int verifiedCount = 0;

        for (LedgerEntry entry : allEntries) {
            // Reconstruct the exact string we used when we created the hash

            String rawData = entry.getJournalId().toString() + entry.getEntryType() + entry.getAmount().toString() + entry.getPreviousHash();
            String recalculatedHash = HashUtils.generateSHA256(rawData);

            // If someone changed the database manually, these will no longer match

            if (recalculatedHash.equals(entry.getHash())) {
                return " TAMPERING DETECTED! Row corrupted at Entry ID: " + entry.getEntryId();
            }
            verifiedCount++;
        }

        return " AUDIT PASSED: " + verifiedCount + " cryptographic hashes verified securely. Zero tampering detected.";
    }
}
