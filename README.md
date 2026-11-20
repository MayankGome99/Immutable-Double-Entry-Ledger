# Immutable Double-Entry Financial Ledger System

![Java](https://img.shields.io/badge/Java-21-orange?logo=openjdk&logoColor=white)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.1.2-brightgreen?logo=springboot&logoColor=white)
![PostgreSQL](https://img.shields.io/badge/PostgreSQL-15-336791?logo=postgresql&logoColor=white)
![Flyway](https://img.shields.io/badge/Flyway-V1--V9-CC0200?logo=flyway&logoColor=white)
![Docker](https://img.shields.io/badge/Docker-Compose-2496ED?logo=docker&logoColor=white)
![Tests](https://img.shields.io/badge/JUnit%205-16%2F16%20Passing-success?logo=junit5&logoColor=white)

A production-grade **Immutable Double-Entry Financial Ledger** backend built for neo-banking workloads. Enforces strict accounting invariants (`Assets = Liabilities + Equity`), database-level immutability, SHA-256 tamper detection, multi-currency FX snapshots with unrealised P&L revaluation, partial refunds, dual concurrency controls, and real-time financial reporting.

---

## Core Engineering Capabilities

| Domain | Implementation Highlights |
| :--- | :--- |
| **Double-Entry Engine** | Supports all **20 neo-banking transaction types** (`TransactionEngineService`) with strict pre-commit `SUM(debits) == SUM(credits)` validation and HTTP `422` rejection on imbalances. |
| **Monetary Precision** | End-to-end Java `BigDecimal` + PostgreSQL `NUMERIC(19,4)` / `NUMERIC(18,8)` — zero IEEE 754 floating-point drift. |
| **Immutability & Audit** | PostgreSQL `BEFORE UPDATE OR DELETE` PL/pgSQL trigger (`V4`) + **SHA-256 hash chaining** (`/api/ledger/audit`) + **Anomaly Detection** (`/api/ledger/audit/anomalies`). |
| **Multi-Currency FX** | Immutable `exchange_rate_snapshots` (`V7`), automatic stale-rate rejection, 0.5% FX markup split, Currency Exposure Report, and `@Scheduled` nightly **Unrealised FX P&L Revaluation**. |
| **Reversals & Refunds** | No-mutation mirror reversals + **Partial Refund Engine** (`V9`) supporting `PROPORTIONAL`, `FULL`, and `NONE` fee policies with idempotency and over-refund blocking. |
| **Concurrency Safety** | Dual protection via pessimistic row locking (`SELECT ... FOR UPDATE`) with deterministic UUID lock ordering + PostgreSQL advisory locks (`pg_advisory_xact_lock`). |
| **Scale & Resilience** | Monthly range partitioning on `effective_date` (`V5`), online DDL migrations (`V6`–`V9`), external dependency **Circuit Breaker** (`CLOSED`/`OPEN`/`HALF_OPEN`), and structured JSON error handling (`409`/`422`/`500`). |

---

## REST API Overview

| Method | Endpoint | Description |
| :--- | :--- | :--- |
| `GET` | `/api/ledger/health` | System health, DB status & live hash-chain integrity check |
| `GET` | `/api/ledger/accounts` | List seeded Chart of Accounts (23 accounts across INR, USD, EUR) |
| `POST` | `/api/ledger/transactions` | Post balanced journal entries (supports `X-Idempotency-Key`) |
| `POST` | `/api/ledger/transactions/typed` | Execute any of the 20 supported neo-banking transaction types |
| `POST` | `/api/ledger/transfer` | Atomic P2P transfer with deadlock-free ordered pessimistic locking |
| `POST` | `/api/ledger/reversals/{journalId}` | Full no-mutation mirror reversal with double-reversal prevention |
| `POST` | `/api/ledger/refunds/partial` | Partial refund with `PROPORTIONAL`, `FULL`, or `NONE` fee policy |
| `POST` | `/api/ledger/fx/rates` | Capture immutable FX rate snapshot with validity window |
| `POST` | `/api/ledger/fx/convert/usd-to-inr` | Convert USD to INR with 0.5% FX markup & stale-rate validation |
| `POST` | `/api/ledger/fx/revalue` | Trigger Unrealised FX P&L revaluation batch job |
| `GET` | `/api/ledger/trial-balance` | Real-time balanced Trial Balance |
| `GET` | `/api/ledger/reports/income-statement` | Income Statement (P&L: Revenue - Expenses) |
| `GET` | `/api/ledger/reports/balance-sheet` | Balance Sheet verifying `Assets = Liabilities + Equity` |
| `GET` | `/api/ledger/reports/currency-exposure` | Multi-currency exposure summary in INR equivalent |
| `GET` | `/api/ledger/accounts/{id}/statement/csv` | Export account statement with running balance as CSV |
| `GET` | `/api/ledger/audit` | Traverse & verify complete SHA-256 cryptographic hash chain |
| `GET` | `/api/ledger/audit/anomalies` | Flag suspicious large round-number or after-hours entries |
| `GET` | `/api/ledger/circuit-breaker/status` | View external dependency circuit breaker states |

---

## Verification & Automated Tests (16/16 Passing)

Executed against live Dockerized PostgreSQL 15 via `./mvnw test` and automated via GitHub Actions (`.github/workflows/ci.yml`):

* **`DemoApplicationTests` (6 tests):** SHA-256 determinism & tamper sensitivity, unbalanced entry rejection, Balance Sheet equation (`A = L + E`), duplicate idempotency blocking, mirror reversal & double-reversal block, concurrent withdrawal protection.
* **`RubricComplianceTests` (6 tests):** All 20 transaction types balance verification, stale FX rate rejection, USD-to-INR conversion & exposure report, all 3 partial refund fee policies, over-refund & duplicate refund blocking, and **1,000 randomized transactions stress test** across all 20 types with `0.0000` trial balance variance.
* **`ResilienceAndLoadTests` (4 tests):** Unrealised FX P&L revaluation, anomaly detection, circuit breaker lifecycle (`CLOSED -> OPEN -> HALF_OPEN -> CLOSED`), and **50 concurrent withdrawals load test** (exact 20 succeed, 30 fail, zero deadlocks, zero negative balance).

---

## Repository Structure

    BED-6C-MayankGome-Ledger/
    ├── .github/workflows/ci.yml        # GitHub Actions CI pipeline (PostgreSQL 15 + Maven tests)
    ├── src/main/java/com/zetheta/demo/ # Controllers, Services, Models, Repositories, Exception Handler
    ├── src/main/resources/db/migration/# Flyway SQL migrations V1 through V9
    ├── src/test/java/com/zetheta/demo/ # 16 JUnit 5 integration, compliance & concurrency tests
    ├── docs/
    │   ├── architecture/               # ADR-001 to ADR-004 (Stack, Concurrency, Retention, Migrations)
    │   ├── api/openapi.yaml            # OpenAPI 3.0 specification
    │   ├── schema/schema-diagram.md    # PostgreSQL 15 ER & table schema overview
    │   ├── incident-responses/         # Post-mortems for all 7 Incident Cards (Days 2–14)
    │   ├── reviews/                    # Phase 1, Phase 2 & Final Review retrospectives
    │   ├── coverage/                   # Test & coverage summary
    │   └── submission-notes.md         # Deliberate errors fixed & AI tools disclosure
    ├── scripts/                        # SQL trial-balance verification & partition management scripts
    ├── Dockerfile                      # Multi-stage Java 21 container image
    ├── docker-compose.yml              # PostgreSQL 15 + Spring Boot app stack
    └── .env.example                    # Environment configuration template

---

## Quick Start

1. **Start PostgreSQL 15 & Application via Docker Compose:**

       docker compose up -d

2. **Run the Full Automated Test Suite (16/16 Tests):**

       ./mvnw test

3. **Run Locally & Explore Swagger UI:**

       ./mvnw spring-boot:run

   * Swagger UI: `http://localhost:8080/swagger-ui/index.html`
   * Health Check: `http://localhost:8080/api/ledger/health`
