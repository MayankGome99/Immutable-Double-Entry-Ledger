# BED-6C Double-Entry Ledger System

A double-entry accounting ledger built with **Java 21**, **Spring Boot 4.1.2**, **PostgreSQL 15**, and **Flyway** (`V1`–`V13`, `validate-on-migrate` enabled).

## Core Implemented Capabilities

* **Double-Entry & Fixed-Point Precision:** All monetary values use Java `BigDecimal` and PostgreSQL `NUMERIC(19,4)` (exchange rates use `NUMERIC(18,8)`). `LedgerService.postTransaction()` enforces `SUM(Debits) == SUM(Credits)` per currency and validates `entry.currency == account.currency` pre-commit.
* **Two-Layer Immutability:**
    1. **Database Trigger (`prevent_ledger_mutation` in `V11`):** Blocks `UPDATE` and `DELETE` on `POSTED`/`REVERSED` rows and blocks modifying core accounting columns.
    2. **Global SHA-256 Hash Chain (`HashUtils` + `LedgerService`):** Serialized via `pg_advisory_xact_lock`, ordered by `chain_seq`, hashing 9 immutable fields per entry and verified end-to-end via `GET /api/v1/ledger/audit/verify`.
* **Concurrency & Atomic Idempotency:** Pessimistic row locks (`SELECT ... FOR UPDATE`) acquired in deterministic sorted UUID order, transactional advisory locks (`pg_advisory_xact_lock`) for withdrawals, reversals, and partial refunds, and atomic `INSERT ... ON CONFLICT DO NOTHING` on `idempotency_keys`.
* **20 Transaction Types & Refunds:** Configurable fee/tax/partial-refund ratios (`application.properties`), `TransactionValidator` per-type rules, full mirror reversals, and DB-derived locked partial refunds (`PROPORTIONAL`, `FULL`, `NONE`).
* **Multi-Currency FX & Revaluation:** Immutable `exchange_rate_snapshots` with stale-rate rejection, net `Assets - Liabilities` currency exposure reporting, stateful delta-only FX revaluation (`fx_revaluation_state` -> `4004`/`5011` via `FxRevaluationScheduler`), and dedicated rounding accounts (`4005`/`5012` in `V13`).
* **Financial Reporting & Resilience:** Labelled JSON trial balance (`asOfDate` + per-currency `balanced` flag), period income statement, balance sheet (`A = L + E`), paginated account statements with running balance (JSON & CSV), `X-Request-Id` filter, and a circuit breaker returning HTTP `503` + `Retry-After`.

## Quick Start (Fresh Clone)

1. Start PostgreSQL 15 cleanly (credentials match `.env.example`, `docker-compose.yml`, and `application.properties`: `ledger_db`, `root` / `rootpassword`, `UTC`):

```powershell
docker compose down -v
docker compose up -d db
```

2. Run the full verification build (compiles, applies Flyway `V1`–`V13`, runs all 46 JUnit tests, and enforces the `>= 80%` JaCoCo line coverage gate):

```powershell
.\mvnw clean verify
```

*(Or run the entire test suite inside Docker without a local JDK/Maven install:)*

```powershell
docker compose run --rm tests
```

3. Start the API locally:

```powershell
.\mvnw spring-boot:run
```

4. View interactive Swagger UI at `http://localhost:8080/swagger-ui.html` or inspect the static OpenAPI 3.0.3 spec at `docs/api/openapi.yaml`.

## Test Suite & Verified Coverage

* **Automated JUnit Tests:** **46/46 passing** across 4 test classes:
    * `DemoApplicationTests` (7 tests): SHA-256 determinism, unbalanced rejection, currency mismatch, idempotency, DB `DELETE` trigger block, real DB tamper detection.
    * `ResilienceAndLoadTests` (4 tests): 50 concurrent withdrawals (double-spend prevention), anomaly detection, idempotent FX revaluation, circuit breaker.
    * `RubricComplianceTests` (29 tests): 20 transaction types parameterized tests, stale FX rate rejection, DB `UPDATE` trigger block, partial refund fee policies, 10-thread concurrent partial refund race test, 1,000 randomized transactions.
    * `ControllerAndConcurrencyTests` (5 tests): MockMvc controller/error-handler tests, `X-Request-Id`, and 10-thread concurrent full reversal race test.
* **JaCoCo Coverage Gate:** Enforced at **>= 80% minimum line coverage** in `pom.xml` (`mvn verify` passes `All coverage checks have been met.`), with **93.0% line coverage (983/1,057 lines)** across core packages (`config`, `controllers`, `exception`, `services`, `validators`). Full HTML report exported to `docs/coverage/index.html`.
* **Load Testing:** k6 scripts located under `tests/load/` (`tests/load/ledger-load-test.js` and `tests/load/migration-during-load.test.js`).

## Documentation Index

* `docs/api/openapi.yaml` — OpenAPI 3.0.3 specification
* `docs/architecture/` — Architecture Decision Records (`ADR-001` through `ADR-004`)
* `docs/rollback.md` — Rollback strategy for migrations `V1`–`V13`
* `docs/case-studies/part-c-case-studies.md` — Real-world case studies (Paytm, Revolut, Wirecard, Razorpay, SVB)
* `docs/incident-responses/` — 7 expanded Incident Card post-mortems (Days 2–14)
* `docs/submission-notes.md` — 8 deliberate brief errors identified, trade-offs, and AI disclosure
* `docs/reviews/final-review.md` — Project retrospective and final review
* `docs/coverage/index.html` — JaCoCo HTML coverage report
* `scripts/` — Partition management (`manage-partitions.sql`, `archive-partition.sql`) and trial balance SQL (`verify-trial-balance.sql`)