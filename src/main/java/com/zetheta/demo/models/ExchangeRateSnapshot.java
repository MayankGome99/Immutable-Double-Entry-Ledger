package com.zetheta.demo.models;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.ZonedDateTime;
import java.util.UUID;

@Entity
@Table(name = "exchange_rate_snapshots")
public class ExchangeRateSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "snapshot_id")
    private UUID snapshotId;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "base_currency", nullable = false, length = 3, columnDefinition = "CHAR(3)")
    private String baseCurrency;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "quote_currency", nullable = false, length = 3, columnDefinition = "CHAR(3)")
    private String quoteCurrency;

    @Column(nullable = false, precision = 18, scale = 8)
    private BigDecimal rate;

    @Column(name = "inverse_rate", nullable = false, precision = 18, scale = 8)
    private BigDecimal inverseRate;

    @Column(nullable = false, length = 50)
    private String source;

    @Column(name = "captured_at", nullable = false)
    private ZonedDateTime capturedAt;

    @Column(name = "valid_from", nullable = false)
    private ZonedDateTime validFrom;

    @Column(name = "valid_until")
    private ZonedDateTime validUntil;

    public ExchangeRateSnapshot() {}

    public UUID getSnapshotId() { return snapshotId; }
    public String getBaseCurrency() { return baseCurrency; }
    public String getQuoteCurrency() { return quoteCurrency; }
    public BigDecimal getRate() { return rate; }
    public BigDecimal getInverseRate() { return inverseRate; }
    public String getSource() { return source; }
    public ZonedDateTime getCapturedAt() { return capturedAt; }
    public ZonedDateTime getValidFrom() { return validFrom; }
    public ZonedDateTime getValidUntil() { return validUntil; }

    public void setSnapshotId(UUID snapshotId) { this.snapshotId = snapshotId; }
    public void setBaseCurrency(String baseCurrency) { this.baseCurrency = baseCurrency; }
    public void setQuoteCurrency(String quoteCurrency) { this.quoteCurrency = quoteCurrency; }
    public void setRate(BigDecimal rate) { this.rate = rate; }
    public void setInverseRate(BigDecimal inverseRate) { this.inverseRate = inverseRate; }
    public void setSource(String source) { this.source = source; }
    public void setCapturedAt(ZonedDateTime capturedAt) { this.capturedAt = capturedAt; }
    public void setValidFrom(ZonedDateTime validFrom) { this.validFrom = validFrom; }
    public void setValidUntil(ZonedDateTime validUntil) { this.validUntil = validUntil; }
}