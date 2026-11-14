package com.zetheta.demo.repositories;


import com.zetheta.demo.models.Account;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface AccountRepository extends JpaRepository<Account, UUID> {
    // Spring Boot apne aap saari basic CRUD (Create, Read, Update, Delete) queries yaha likh dega.
}