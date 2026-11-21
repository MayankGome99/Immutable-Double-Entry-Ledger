# BED-6C Immutable Double-Entry Financial Ledger

[![CI](https://github.com/MayankGome99/Immutable-Double-Entry-Ledger/actions/workflows/ci.yml/badge.svg)](https://github.com/MayankGome99/Immutable-Double-Entry-Ledger/actions/workflows/ci.yml)
![Java](https://img.shields.io/badge/Java-21-ED8B00?style=flat&logo=openjdk&logoColor=white)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.1.2-6DB33F?style=flat&logo=springboot&logoColor=white)
![PostgreSQL](https://img.shields.io/badge/PostgreSQL-15-4169E1?style=flat&logo=postgresql&logoColor=white)
![Flyway](https://img.shields.io/badge/Flyway-V1--V13-CC0200?style=flat&logo=flyway&logoColor=white)
![Docker](https://img.shields.io/badge/Docker-Compose-2496ED?style=flat&logo=docker&logoColor=white)
![Tests](https://img.shields.io/badge/JUnit%20Tests-46%2F46%20Passing-brightgreen?style=flat&logo=junit5&logoColor=white)
![Coverage](https://img.shields.io/badge/JaCoCo%20Line%20Coverage-93.0%25-brightgreen?style=flat)

A production-style double-entry accounting ledger and immutable audit engine built with **Java 21**, **Spring Boot 4.1.2**, **PostgreSQL 15**, and **Flyway** (`V1`–`V13`, `validate-on-migrate` enabled).

---

## Key Metrics

| Metric | Verified Value |
| :--- | :--- |
| **Transaction Types** | 20 (`TransactionType` enum + per-type `TransactionValidator` rules) |
| **Chart of Accounts** | 25 seeded accounts across Assets, Liabilities, Equity, Revenue, Expenses |
| **Schema Migrations** | 13 Flyway migrations (`V1`–`V13`) with monthly range partitioning (`V5`) |
| **Automated Tests** | **46/46 passing** (unit, JPA/PostgreSQL integration, MockMvc, concurrency) |
| **JaCoCo Coverage** | **93.0% line coverage** (`983/1,057` lines) — CI gate enforced at `>= 80%` |
| **Concurrency Proofs** | 50-thread withdrawal, 20-thread bi-directional P2P, 10-thread refund & reversal races |

---

## Core Implemented Capabilities

* **Double-Entry & Fixed-Point Precision:** All monetary values use Java `BigDecimal` and PostgreSQL `NUMERIC(19,4)` (exchange rates use `NUMERIC(18,8)`). `LedgerService.postTransaction()` enforces `SUM(Debits) == SUM(Credits)` per currency and validates `entry.currency == account.currency` pre-commit.
* **Two-Layer Immutability:**
  1. **Database Trigger (`prevent_ledger_mutation` in `V11`):** Blocks `UPDATE` and `DELETE` on `POSTED`/`REVERSED` rows and blocks modifying core accounting columns.
  2. **Global SHA-256 Hash Chain (`HashUtils` + `LedgerService`):** Serialized via `pg_advisory_xact_lock`, ordered by `chain_seq`, hashing 9 immutable fields per entry and verified end-to-end via `GET /api/v1/audit/verify`.
* **Concurrency & Atomic Idempotency:** Pessimistic row locks (`SELECT ... FOR UPDATE`) acquired in deterministic sorted UUID order, transactional advisory locks (`pg_advisory_xact_lock`) for withdrawals, reversals, and partial refunds, and atomic `INSERT ... ON CONFLICT DO NOTHING` on `idempotency_keys`.
* **20 Transaction Types & Refunds:** Configurable fee/tax/partial-refund ratios (`application.properties`), `TransactionValidator` per-type rules, full mirror reversals, and DB-derived locked partial refunds (`PROPORTIONAL`, `FULL`, `NONE`).
* **Multi-Currency FX & Revaluation:** Immutable `exchange_rate_snapshots` with stale-rate rejection, net `Assets - Liabilities` currency exposure reporting, stateful delta-only FX revaluation (`fx_revaluation_state` → `4004`/`5011` via `FxRevaluationScheduler`), and dedicated rounding accounts (`4005`/`5012` in `V13`).
* **Financial Reporting & Resilience:** Labelled JSON trial balance (`asOfDate` + per-currency `balanced` flag), period income statement, balance sheet (`A = L + E`), paginated account statements with running balance (JSON & CSV), `X-Request-Id` filter, and a circuit breaker returning HTTP `503` + `Retry-After`.

---

## Quick Start (Fresh Clone)

1. **Start PostgreSQL 15 cleanly** (dev-only local credentials match `.env.example`, `docker-compose.yml`, and `application.properties`: `ledger_db`, `root` / `rootpassword`, `UTC`):

   ```powershell
   docker compose down -v
   docker compose up -d db
   ```

2. **Run the full verification build** (compiles, applies Flyway `V1`–`V13`, runs all 46 JUnit tests, and enforces the `>= 80%` JaCoCo line coverage gate):

   ```powershell
   .\mvnw clean verify
   ```

   *(Or run the entire test suite inside Docker without a local JDK/Maven install:)*

   ```powershell
   docker compose run --rm tests
   ```

3. **Start the API locally:**

   ```powershell
   .\mvnw spring-boot:run
   ```

4. **Explore the API:** Open interactive Swagger UI at `http://localhost:8080/swagger-ui.html` or inspect the static OpenAPI 3.0.3 spec at [`docs/api/openapi.yaml`](docs/api/openapi.yaml).

---

## Test Suite & Verified Coverage

* **Automated JUnit Tests:** **46/46 passing** across 4 test classes:
  * `DemoApplicationTests` (7 tests): SHA-256 determinism, unbalanced rejection, currency mismatch, idempotency, DB `DELETE` trigger block, real DB tamper detection.
  * `ResilienceAndLoadTests` (4 tests): 50 concurrent withdrawals (double-spend prevention), anomaly detection, idempotent FX revaluation, circuit breaker.
  * `RubricComplianceTests` (29 tests): 20 transaction types parameterized tests, stale FX rate rejection, DB `UPDATE` trigger block, partial refund fee policies, 10-thread concurrent partial refund race test, 1,000 randomized transactions.
  * `ControllerAndConcurrencyTests` (6 tests): MockMvc controller/error-handler tests, `X-Request-Id`, 10-thread concurrent full reversal race test, and 20-thread bi-directional P2P deadlock-free transfer test.
* **JaCoCo Coverage Gate:** Enforced at **>= 80% minimum line coverage** in `pom.xml` (`mvn verify` passes `All coverage checks have been met.`), achieving **93.0% overall line coverage (`983/1,057` lines)**. Full HTML report exported to [`docs/coverage/index.html`](docs/coverage/index.html).
* **Load Testing:** k6 scripts located under `tests/load/` ([`ledger-load-test.js`](tests/load/ledger-load-test.js) and [`migration-during-load.test.js`](tests/load/migration-during-load.test.js)).

---

## Documentation Index

| Path | Description |
| :--- | :--- |
| [`docs/api/openapi.yaml`](docs/api/openapi.yaml) | OpenAPI 3.0.3 specification |
| [`docs/architecture/`](docs/architecture/) | Architecture Decision Records (`ADR-001` through `ADR-004`) |
| [`docs/rollback.md`](docs/rollback.md) | Rollback strategy for migrations `V1`–`V13` |
| [`docs/case-studies/part-c-case-studies.md`](docs/case-studies/part-c-case-studies.md) | Real-world case studies (Paytm, Revolut, Wirecard, Razorpay, SVB) |
| [`docs/incident-responses/`](docs/incident-responses/) | 7 expanded Incident Card post-mortems (Days 2–14) |
| [`docs/submission-notes.md`](docs/submission-notes.md) | 8 deliberate brief errors identified, trade-offs, and AI disclosure |
| [`docs/reviews/final-review.md`](docs/reviews/final-review.md) | Project retrospective and final review |
| [`docs/coverage/index.html`](docs/coverage/index.html) | JaCoCo HTML coverage report |
| [`scripts/`](scripts/) | Partition management (`manage-partitions.sql`, `archive-partition.sql`) and trial balance SQL |