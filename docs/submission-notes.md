# ZeTheta Ledger — Submission Notes

## 1. Architecture & Core Design Decisions
- **Double-Entry Accounting Engine:** Every transaction enforces strict balance validation (`Total Debits == Total Credits`) before persistence, ensuring zero unbalanced entries in the ledger.
- **Financial Precision:** Used Java `BigDecimal` paired with PostgreSQL `NUMERIC(19,4)` instead of floating-point types (`float`/`double`) to eliminate rounding errors.
- **Database-Level Immutability:** Implemented a PostgreSQL `BEFORE UPDATE OR DELETE` trigger (`V4__create_immutability_triggers.sql`) to block modifications or deletions of `POSTED` ledger rows. Corrections are handled strictly through offsetting reversal entries.
- **Concurrency & Double-Spend Prevention:** Applied pessimistic row-level locking (`findByIdWithLock`) on `accounts` during debits/transfers to prevent race conditions under concurrent load.
- **Cryptographic Tamper Detection:** Chained SHA-256 hashes across entries (`hash` + `previous_hash`) with an `/api/ledger/audit` verifier that recomputes hashes to detect unauthorized database edits.
- **Scalability & Zero-Downtime Evolution:**
    - `V5`: Migrated `ledger_entries` to monthly PostgreSQL **Range Partitioning** on `effective_date` (with a `DEFAULT` safety partition) to enable partition pruning and cold-storage archival.
    - `V6`: Applied non-blocking **Online DDL** (`ADD COLUMN` with default + composite index on `(account_id, effective_date DESC)`) with a documented rollback strategy.

## 2. Key Debugging Milestone (Stress Test Hash Mismatch)
During the Day 15 stress test, the audit verifier flagged a false-positive tampering alert due to storage formatting differences between Java and PostgreSQL:
- `NUMERIC(19,4)` stored `10.00` as `10.0000`.
- `CHAR(64)` right-padded `"GENESIS_HASH"` with trailing spaces.

**Fix:** Normalized hash inputs in both `postTransaction` and `verifyAuditTrail` using `.stripTrailingZeros().toPlainString()` for amounts and `.trim()` for hashes.

## 3. Stress Test Benchmark Results (Day 15)
- **Endpoint:** `POST /api/ledger/stress-test`
- **Volume:** 1,000 balanced transactions (2,000 ledger entries)
- **Execution Time:** `9,360 ms` (~106.8 transactions/sec including row locking & SHA-256 hashing)
- **Audit Verification:** `AUDIT PASSED: 2000 cryptographic hashes verified securely. Zero tampering detected.`

## 4. AI Assistance Acknowledgement
AI tools were used during development for architectural guidance, SQL migration syntax reference, and debugging assistance. All core flows, database migrations, and API endpoints were manually integrated, tested, and verified locally via Docker, PostgreSQL, and Swagger UI.