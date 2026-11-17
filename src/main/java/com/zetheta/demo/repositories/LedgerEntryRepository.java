package com.zetheta.demo.repositories;


import com.zetheta.demo.models.LedgerEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, UUID> {

    // Calculates: Sum of Credits - Sum of Debits

    @Query("SELECT COALESCE(SUM(CASE WHEN e.entryType = 'CREDIT' THEN e.amount ELSE -e.amount END),0) "+
            "FROM LedgerEntry e WHERE e.account.id = :accountId AND e.status = 'POSTED' ")
    BigDecimal calculateBalance(@Param("accountId") UUID accountId);

    List<LedgerEntry> findByJournalId(UUID journalId);

    //The Trial Balance Query

    @Query("SELECT a.accountName, " +
            "SUM(CASE WHEN e.entryType = 'DEBIT' THEN e.amount ELSE 0 END), " +
            "SUM(CASE WHEN e.entryType = 'CREDIT' THEN e.amount ELSE 0 END) " +
            "FROM LedgerEntry e JOIN e.account a " +
            "WHERE e.status = 'POSTED' " +
            "GROUP BY a.accountName")
    List<Object[]> generateTrialBalance();

}