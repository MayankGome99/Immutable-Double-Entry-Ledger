package com.zetheta.demo.services;

import com.zetheta.demo.models.LedgerEntry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.zetheta.demo.repositories.LedgerEntryRepository;
import com.zetheta.demo.utils.HashUtils;

import java.math.BigDecimal;
import java.util.List;

@Service
public class LedgerService {

    private final LedgerEntryRepository repository;

    public LedgerService(LedgerEntryRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public void postTransaction(List<LedgerEntry> entries) {
        BigDecimal totalDebits = BigDecimal.ZERO;
        BigDecimal totalCredits = BigDecimal.ZERO;

        // for calculation total debits and credits

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
            entry.setJournalId(generatedJournalId);
            entry.setEffectiveDate(now);
            entry.setCreatedBy("SYSTEM");

            entry.setIdempotencyKey(java.util.UUID.randomUUID().toString());

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
}
