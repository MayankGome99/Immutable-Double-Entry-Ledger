package com.zetheta.demo.models;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "ledger_entries")
public class LedgerEntry {

    @Id
    @Column(name = "entry_id")
    private UUID entryId;

    @Column(name = "journal_id", nullable = false, updatable = false)
    private UUID journalId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "account_id", nullable = false, updatable = false)
    private Account account;

    @Column(name = "entry_type", nullable = false, updatable = false)
    private String entryType; // debit OR credit code

    @Column(nullable = false, precision = 19, scale = 4, updatable = false)
    private BigDecimal amount;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "hash", nullable = false, length = 64, updatable = false, columnDefinition = "CHAR(64)")
    private String hash;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "previous_hash", length = 64, updatable = false, columnDefinition = "CHAR(64)")
    private String previousHash;

    @Column(nullable = false, length = 20)
    private String status;

    // Getters
    public UUID getEntryId() { return entryId; }
    public UUID getJournalId() { return journalId; }
    public Account getAccount() { return account; }
    public String getEntryType() { return entryType; }
    public BigDecimal getAmount() { return amount; }
    public String getHash() { return hash; }
    public String getPreviousHash() { return previousHash; }
    public String getStatus() { return status; }

    // Setters
    public void setEntryId(UUID entryId) { this.entryId = entryId; }
    public void setJournalId(UUID journalId) { this.journalId = journalId; }
    public void setAccount(Account account) { this.account = account; }
    public void setEntryType(String entryType) { this.entryType = entryType; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public void setHash(String hash) { this.hash = hash; }
    public void setPreviousHash(String previousHash) { this.previousHash = previousHash; }
    public void setStatus(String status) { this.status = status; }

    public LedgerEntry() {}

}
