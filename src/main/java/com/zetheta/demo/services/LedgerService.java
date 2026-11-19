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
            // Lock the account row in PostgreSQL for EVERY entry to prevent concurrent double-spends
            Account account = accountRepository.findByIdWithLock(entry.getAccount().getId())
                    .orElseThrow(() -> new IllegalArgumentException("Account not found"));

            entry.setAccount(account);  // Re-attach full database object to the entry

            // Accounting Rules: Assets decrease with CREDIT. Liabilities decrease with DEBIT.
            boolean isAssetDecrease = "Asset".equals(account.getAccountType()) && "CREDIT".equals(entry.getEntryType());
            boolean isLiabilityDecrease = "Liability".equals(account.getAccountType()) && "DEBIT".equals(entry.getEntryType());

            // Only check the balance if money is actively leaving the account
            if (isAssetDecrease || isLiabilityDecrease) {
                BigDecimal currentBalance = repository.calculateBalance(account.getId());
                if (currentBalance.compareTo(entry.getAmount()) < 0) {
                    throw new IllegalArgumentException("Transaction failed: Insufficient balance. Available: " + currentBalance);
                }
            }

            entry.setJournalId(generatedJournalId);
            entry.setEffectiveDate(now);


            if (entry.getCreatedBy() == null) {
                entry.setCreatedBy("SYSTEM");
            }

            entry.setIdempotencyKey(java.util.UUID.randomUUID().toString());

            if (entry.getCurrency() == null) {
                entry.setCurrency("INR");
            }

            // Hash the actual transaction data, NOT the database ID!
            String cleanAmount = entry.getAmount().stripTrailingZeros().toPlainString();
            String rawData = entry.getJournalId().toString() + entry.getEntryType() + cleanAmount + previousHash.trim();

            entry.setHash(HashUtils.generateSHA256(rawData));
            entry.setPreviousHash(previousHash);

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
            String cleanAmount = entry.getAmount().stripTrailingZeros().toPlainString();
            String rawData = entry.getJournalId().toString() + entry.getEntryType() + cleanAmount + entry.getPreviousHash().trim();
            String recalculatedHash = HashUtils.generateSHA256(rawData);

            // If someone changed the database manually, these will no longer match

            if (!recalculatedHash.equals(entry.getHash())) {
                return " TAMPERING DETECTED! Row corrupted at Entry ID: " + entry.getEntryId();
            }
            verifiedCount++;
        }

        return " AUDIT PASSED: " + verifiedCount + " cryptographic hashes verified securely. Zero tampering detected.";
    }
    // Automated P2P Transfer Engine

    @Transactional
    public void transferFunds(java.util.UUID fromAccountId, java.util.UUID toAccountId, BigDecimal amount) {
        Account fromAccount = accountRepository.findByIdWithLock(fromAccountId)
                .orElseThrow(() -> new IllegalArgumentException("Senders Account not found"));
        Account toAccount = accountRepository.findByIdWithLock(toAccountId)
                .orElseThrow(() -> new IllegalArgumentException("Receivers Account not found"));


        LedgerEntry debitEntry = new LedgerEntry();
        debitEntry.setAccount(fromAccount);
        debitEntry.setEntryType("DEBIT");
        debitEntry.setAmount(amount);
        debitEntry.setCurrency(fromAccount.getCurrency());
        debitEntry.setCreatedBy("SYSTEM_P2P");

        LedgerEntry creditEntry = new LedgerEntry();
        creditEntry.setAccount(toAccount);
        creditEntry.setEntryType("CREDIT");
        creditEntry.setAmount(amount);
        creditEntry.setCurrency(toAccount.getCurrency());
        creditEntry.setCreatedBy("SYSTEM_P2P");

        // Pass the automatically generated entries into your secure locking engine

        postTransaction(java.util.Arrays.asList(debitEntry, creditEntry));
    }
}
