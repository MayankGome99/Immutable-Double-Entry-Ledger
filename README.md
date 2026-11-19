# Immutable Double-Entry Financial Ledger System

A production-style financial ledger backend built with **Java 21, Spring Boot 4.1.2, PostgreSQL 15, Flyway, and Docker**. Designed for strict double-entry accounting, cryptographic tamper detection, concurrency safety, idempotency, financial reporting, and partitioned scalability.

## Tech Stack
- **Backend:** Java 21, Spring Boot 4.1.2, Spring Data JPA (Hibernate)
- **Database & Migrations:** PostgreSQL 15 (Dockerized), Flyway (`V1`–`V7`)
- **API Documentation:** OpenAPI 3 / Swagger UI (`springdoc-openapi`)
- **Testing:** JUnit 5 & Spring Boot Integration Tests

## Key Features
- **Strict Double-Entry Validation:** Rejects any unbalanced transaction (`totalDebits != totalCredits`) with HTTP 422 before persistence.
- **Exact Monetary Precision:** Uses Java `BigDecimal` and PostgreSQL `NUMERIC(19,4)` to eliminate IEEE 754 floating-point rounding errors.
- **Idempotency Enforcement:** Supports `X-Idempotency-Key` headers and database-level uniqueness to prevent duplicate transaction processing (HTTP 409 Conflict).
- **Concurrency & Double-Spend Prevention:** Uses pessimistic row-level locking (`SELECT ... FOR UPDATE`) with deterministic UUID lock ordering to prevent deadlocks and negative balances.
- **Database-Level Immutability:** PostgreSQL `BEFORE UPDATE OR DELETE` PL/pgSQL triggers prevent any mutation of posted ledger entries.
- **No-Mutation Reversals:** Corrective reversals mirror original debit/credit entries and block duplicate reversals.
- **Cryptographic Audit Trail:** SHA-256 hash chaining across ledger entries with an on-demand verification endpoint.
- **Monthly Range Partitioning & Online DDL:** `ledger_entries` partitioned by `effective_date` (`V5`), zero-downtime column + composite indexing (`V6`), and multi-currency FX rate snapshots + idempotency/reversals tables (`V7`).
- **Financial Reporting Suite:** Trial Balance, Income Statement (P&L), Balance Sheet (`Assets = Liabilities + Equity`), Account Statements (JSON & CSV export), and System Health Check.

## Flyway Migrations (`V1`–`V7`)
| Version | Description |
| :--- | :--- |
| `V1` | Create `accounts` table (Chart of Accounts schema) |
| `V2` | Seed 19+ neo-banking Chart of Accounts entries |
| `V3` | Create `ledger_entries` table with audit & hash columns |
| `V4` | Create `enforce_ledger_immutability` PL/pgSQL trigger |
| `V5` | Convert `ledger_entries` to monthly range partitioning |
| `V6` | Zero-downtime column addition & composite index |
| `V7` | Add `exchange_rate_snapshots`, `idempotency_keys`, and `reversals` tables |

## API Endpoints
| Method | Endpoint | Description |
| :--- | :--- | :--- |
| `GET` | `/api/ledger/health` | Database, entry counts & audit health status |
| `GET` | `/api/ledger/accounts` | List all Chart of Accounts |
| `GET` | `/api/ledger/entries` | List all posted ledger entries |
| `POST` | `/api/ledger/transactions` | Post balanced journal entries (supports `X-Idempotency-Key`) |
| `POST` | `/api/ledger/transfer` | Atomic P2P transfer with deadlock-safe locking |
| `POST` | `/api/ledger/reversals/{journalId}` | Post mirror reversal entry (no-mutation principle) |
| `GET` | `/api/ledger/trial-balance` | Generate real-time Trial Balance |
| `GET` | `/api/ledger/reports/income-statement` | Generate Income Statement (P&L) |
| `GET` | `/api/ledger/reports/balance-sheet` | Generate Balance Sheet (`A = L + E` check) |
| `GET` | `/api/ledger/accounts/{id}/statement` | Fetch account statement (JSON) |
| `GET` | `/api/ledger/accounts/{id}/statement/csv` | Export account statement as CSV |
| `GET` | `/api/ledger/audit` | Verify full SHA-256 hash chain integrity |
| `POST` | `/api/ledger/stress-test` | Run 1,000-transaction (2,000-entry) stress test + audit |

## How to Run Locally
1. Copy `.env.example` to `.env` (or use default Docker credentials).
2. Start PostgreSQL via Docker:
   ```bash
   docker compose up -d