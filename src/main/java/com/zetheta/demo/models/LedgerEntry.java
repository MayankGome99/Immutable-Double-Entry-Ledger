package com.zetheta.demo.models;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.ZonedDateTime;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "ledger_entries")
public class LedgerEntry {

    @Id
    @Column(name = "entry_id", nullable = false, updatable = false)
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

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 3, updatable = false, columnDefinition = "CHAR(3)")
    private String currency;

    @Column(name = "effective_date", nullable = false)
    private ZonedDateTime effectiveDate;

    @Column(name = "created_by", nullable = false)
    private String createdBy;

    @Column(name = "idempotency_key", nullable = false, unique = true, length = 64)
    private String idempotencyKey;

    @Column(nullable = false, length = 20)
    private String status;

    // Default constructor required by JPA
    public LedgerEntry() {}

    // ==================== GETTERS ====================
    public UUID getEntryId() { return entryId; }
    public UUID getJournalId() { return journalId; }
    public Account getAccount() { return account; }
    public String getEntryType() { return entryType; }
    public BigDecimal getAmount() { return amount; }
    public String getHash() { return hash; }
    public String getPreviousHash() { return previousHash; }
    public String getCurrency() { return currency; }
    public ZonedDateTime getEffectiveDate() { return effectiveDate; }
    public String getCreatedBy() { return createdBy; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getStatus() { return status; }

    // ==================== SETTERS ====================
    public void setEntryId(UUID entryId) { this.entryId = entryId; }
    public void setJournalId(UUID journalId) { this.journalId = journalId; }
    public void setAccount(Account account) { this.account = account; }
    public void setEntryType(String entryType) { this.entryType = entryType; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public void setHash(String hash) { this.hash = hash; }
    public void setPreviousHash(String previousHash) { this.previousHash = previousHash; }
    public void setCurrency(String currency) { this.currency = currency; }
    public void setEffectiveDate(ZonedDateTime effectiveDate) { this.effectiveDate = effectiveDate; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
    public void setStatus(String status) { this.status = status; }

}