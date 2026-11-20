# Immutable Double-Entry Financial Ledger System

A production-grade financial ledger backend built with Java 21, Spring Boot 4.1.2, PostgreSQL 15, Flyway (V1-V9), Docker, GitHub Actions CI, and JUnit 5.

## Key Capabilities
- All 20 Neo-Banking Transaction Types: Balanced journal handlers (TransactionEngineService) verified across 500 randomized transactions with 0.0000 trial balance discrepancy.
- Exact Monetary Precision: Java BigDecimal + PostgreSQL NUMERIC(19,4) / NUMERIC(18,8) with zero IEEE 754 floating-point drift.
- Immutability & Audit Trail: PostgreSQL BEFORE UPDATE OR DELETE PL/pgSQL trigger (V4), SHA-256 hash chaining (/api/ledger/audit), and anomaly detection (/api/ledger/audit/anomalies).
- Multi-Currency FX & Unrealised P&L: Immutable exchange_rate_snapshots (V7), stale-rate rejection, 0.5% FX markup, Currency Exposure Report, and nightly/on-demand unrealised FX revaluation job (/api/ledger/fx/revalue).
- Partial Refunds & No-Mutation Reversals: Mirror reversals plus partial refunds (V9) with PROPORTIONAL, FULL, and NONE fee policies, idempotency protection, and over-refund blocking.
- Concurrency Model: Dual protection via pessimistic row locking (SELECT FOR UPDATE) with deterministic UUID ordering + PostgreSQL advisory locks (pg_advisory_xact_lock), proven via 50-concurrent-withdrawal load test.
- Resilience & Operations: Circuit Breaker (CLOSED/OPEN/HALF_OPEN) for external dependencies, structured JSON global error handling (409/422/500), monthly range partitioning (V5), and online DDL migrations (V6-V9).
- Automated Test Suite & CI: 16/16 JUnit 5 integration & load tests passing (./mvnw test) + GitHub Actions CI pipeline (.github/workflows/ci.yml).

## How to Run Locally
    docker compose up -d
    ./mvnw test
    ./mvnw spring-boot:run

Swagger UI: http://localhost:8080/swagger-ui/index.html
