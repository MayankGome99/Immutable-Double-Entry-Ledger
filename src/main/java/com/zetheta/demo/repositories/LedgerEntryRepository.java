package com.zetheta.demo.repositories;

import com.zetheta.demo.models.LedgerEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, UUID> {

    // Normal balance calculation adjusted by account type (Asset/Expense = Debit-Credit, others = Credit-Debit)
    @Query("SELECT COALESCE(SUM(CASE " +
            "WHEN a.accountType IN ('Asset', 'Expense') THEN (CASE WHEN e.entryType = 'DEBIT' THEN e.amount ELSE -e.amount END) " +
            "ELSE (CASE WHEN e.entryType = 'CREDIT' THEN e.amount ELSE -e.amount END) END), 0) " +
            "FROM LedgerEntry e JOIN e.account a WHERE a.id = :accountId AND e.status = 'POSTED'")
    BigDecimal calculateBalance(@Param("accountId") UUID accountId);

    boolean existsByIdempotencyKey(String idempotencyKey);

    List<LedgerEntry> findByJournalId(UUID journalId);

    // Trial Balance Query
    @Query("SELECT a.accountCode, a.accountName, a.accountType, " +
            "COALESCE(SUM(CASE WHEN e.entryType = 'DEBIT' THEN e.amount ELSE 0 END), 0), " +
            "COALESCE(SUM(CASE WHEN e.entryType = 'CREDIT' THEN e.amount ELSE 0 END), 0) " +
            "FROM LedgerEntry e JOIN e.account a " +
            "WHERE e.status = 'POSTED' " +
            "GROUP BY a.accountCode, a.accountName, a.accountType " +
            "ORDER BY a.accountCode")
    List<Object[]> generateTrialBalance();

    // Aggregated totals by Account Type for Income Statement & Balance Sheet
    @Query("SELECT a.accountType, " +
            "COALESCE(SUM(CASE WHEN e.entryType = 'DEBIT' THEN e.amount ELSE 0 END), 0), " +
            "COALESCE(SUM(CASE WHEN e.entryType = 'CREDIT' THEN e.amount ELSE 0 END), 0) " +
            "FROM LedgerEntry e JOIN e.account a " +
            "WHERE e.status = 'POSTED' " +
            "GROUP BY a.accountType")
    List<Object[]> getAccountTypeTotals();

    // Fetch Account Statement
    List<LedgerEntry> findByAccount_IdOrderByEffectiveDateDesc(UUID accountId);
}