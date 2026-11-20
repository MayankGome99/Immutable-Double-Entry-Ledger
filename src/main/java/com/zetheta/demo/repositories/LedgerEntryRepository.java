package com.zetheta.demo.repositories;

import com.zetheta.demo.models.LedgerEntry;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, UUID> {

    @Query("SELECT COALESCE(SUM(CASE " +
            "WHEN a.accountType IN ('Asset', 'Expense') THEN (CASE WHEN e.entryType = 'DEBIT' THEN e.amount ELSE -e.amount END) " +
            "ELSE (CASE WHEN e.entryType = 'CREDIT' THEN e.amount ELSE -e.amount END) END), 0) " +
            "FROM LedgerEntry e JOIN e.account a WHERE a.id = :accountId AND e.status = 'POSTED'")
    BigDecimal calculateBalance(@Param("accountId") UUID accountId);

    @Query("SELECT COALESCE(SUM(CASE " +
            "WHEN a.accountType IN ('Asset', 'Expense') THEN (CASE WHEN e.entryType = 'DEBIT' THEN e.amount ELSE -e.amount END) " +
            "ELSE (CASE WHEN e.entryType = 'CREDIT' THEN e.amount ELSE -e.amount END) END), 0) " +
            "FROM LedgerEntry e JOIN e.account a " +
            "WHERE a.id = :accountId AND TRIM(e.currency) = :currency AND e.status = 'POSTED'")
    BigDecimal calculateBalanceByCurrency(@Param("accountId") UUID accountId, @Param("currency") String currency);

    boolean existsByIdempotencyKey(String idempotencyKey);

    List<LedgerEntry> findByJournalId(UUID journalId);

    // Per-currency Trial Balance with normal-balance sign & optional as-of date
    @Query("SELECT a.accountCode, a.accountName, a.accountType, TRIM(e.currency), " +
            "COALESCE(SUM(CASE WHEN e.entryType = 'DEBIT' THEN e.amount ELSE 0 END), 0), " +
            "COALESCE(SUM(CASE WHEN e.entryType = 'CREDIT' THEN e.amount ELSE 0 END), 0), " +
            "COALESCE(SUM(CASE " +
            "WHEN a.accountType IN ('Asset', 'Expense') THEN (CASE WHEN e.entryType = 'DEBIT' THEN e.amount ELSE -e.amount END) " +
            "ELSE (CASE WHEN e.entryType = 'CREDIT' THEN e.amount ELSE -e.amount END) END), 0) " +
            "FROM LedgerEntry e JOIN e.account a " +
            "WHERE e.status = 'POSTED' AND e.effectiveDate <= :asOfDate " +
            "GROUP BY a.accountCode, a.accountName, a.accountType, TRIM(e.currency) " +
            "ORDER BY TRIM(e.currency), a.accountCode")
    List<Object[]> generateTrialBalanceAsOf(@Param("asOfDate") ZonedDateTime asOfDate);

    default List<Object[]> generateTrialBalance() {
        return generateTrialBalanceAsOf(ZonedDateTime.now().plusYears(10));
    }

    @Query("SELECT a.accountType, " +
            "COALESCE(SUM(CASE WHEN e.entryType = 'DEBIT' THEN e.amount ELSE 0 END), 0), " +
            "COALESCE(SUM(CASE WHEN e.entryType = 'CREDIT' THEN e.amount ELSE 0 END), 0) " +
            "FROM LedgerEntry e JOIN e.account a " +
            "WHERE e.status = 'POSTED' AND TRIM(e.currency) = :currency " +
            "AND e.effectiveDate >= :fromDate AND e.effectiveDate <= :toDate " +
            "GROUP BY a.accountType")
    List<Object[]> getAccountTypeTotalsByPeriod(@Param("fromDate") ZonedDateTime fromDate,
                                                @Param("toDate") ZonedDateTime toDate,
                                                @Param("currency") String currency);

    @Query("SELECT a.accountType, " +
            "COALESCE(SUM(CASE WHEN e.entryType = 'DEBIT' THEN e.amount ELSE 0 END), 0), " +
            "COALESCE(SUM(CASE WHEN e.entryType = 'CREDIT' THEN e.amount ELSE 0 END), 0) " +
            "FROM LedgerEntry e JOIN e.account a " +
            "WHERE e.status = 'POSTED' AND TRIM(e.currency) = :currency " +
            "AND e.effectiveDate <= :asOfDate " +
            "GROUP BY a.accountType")
    List<Object[]> getAccountTypeTotalsAsOf(@Param("asOfDate") ZonedDateTime asOfDate,
                                            @Param("currency") String currency);

    List<LedgerEntry> findByAccount_IdOrderByEffectiveDateDesc(UUID accountId);

    List<LedgerEntry> findByAccount_IdOrderByEffectiveDateAsc(UUID accountId);

    Page<LedgerEntry> findByAccount_IdAndEffectiveDateBetweenOrderByEffectiveDateAsc(
            UUID accountId, ZonedDateTime from, ZonedDateTime to, Pageable pageable);
}