package com.zetheta.demo.repositories;

import com.zetheta.demo.models.ExchangeRateSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ExchangeRateSnapshotRepository extends JpaRepository<ExchangeRateSnapshot, UUID> {
    Optional<ExchangeRateSnapshot> findTopByBaseCurrencyAndQuoteCurrencyOrderByCapturedAtDesc(
            String baseCurrency, String quoteCurrency);
}