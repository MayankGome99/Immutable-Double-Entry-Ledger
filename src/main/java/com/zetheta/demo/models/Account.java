package com.zetheta.demo.models;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.ZonedDateTime;
import java.util.UUID;

@Entity
@Table(name = "accounts")
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "account_code", unique = true, nullable = false, length = 10)
    private String accountCode;

    @Column(name = "account_name", nullable = false, length = 100)
    private String accountName;

    @Column(name = "account_type", nullable = false, length = 20)
    private String accountType;

    @Column(name = "sub_type", nullable = false, length = 50)
    private String subtype;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 3, columnDefinition = "CHAR(3)")
    private String currency;

    @Column(name = "created_at", updatable = false)
    private ZonedDateTime createdAt;

    // Default constructor required by JPA
    public Account() {}

    // Getters
    public UUID getId() { return id; }
    public String getAccountCode() { return accountCode; }
    public String getAccountName() { return accountName; }
    public String getAccountType() { return accountType; }
    public String getSubtype() { return subtype; }
    public String getCurrency() { return currency; }
}