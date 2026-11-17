package com.zetheta.demo.repositories;


import com.zetheta.demo.models.Account;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface AccountRepository extends JpaRepository<Account, UUID> {
    // Spring Boot apne aap saari basic CRUD (Create, Read, Update, Delete) queries yaha likh dega.


    // This is the Bouncer. It locks the account row until the transaction is done\/


    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM Account a WHERE a.id = :id")
    Optional<Account> findByIdWithLock(@Param("id") UUID id );
}