# Part C: Real-World Case Study Analyses

## Case Study 1: Paytm Payments Bank Ledger Reconciliation Crisis (2023–2024)

### 1. Schema Design for 10 Million Transactions/Day
- **Native Range Partitioning:** `ledger_entries` is range-partitioned monthly by `effective_date` (`V5__partition_ledger_entries.sql`), enabling PostgreSQL partition pruning so end-of-day queries scan only the active month's partition instead of historical multi-terabyte tables.
- **Composite Indexing:** `idx_ledger_entries_account_date (account_id, effective_date DESC)` (`V6__zero_downtime_add_column_and_index.sql`) supports fast account statement and reconciliation lookups without full table scans.
- **Materialized Balance Snapshots:** `balance_snapshots` (`V12`) caches account balances per commit so balance reads remain O(1) while the source of truth stays append-only.
- **Batch Processing:** High-volume micro-transactions (UPI/cashback) should be ingested via partitioned worker queues grouped by `account_id` hash to eliminate cross-worker lock contention.

### 2. Automated Near-Real-Time Trial Balance Discrepancy Detection
- Run an incremental per-currency trial balance check (`GET /api/v1/ledger/trial-balance?asOfDate=...`) on a 5-minute schedule and immediately at EOD cut-off.
- Because `LedgerService.postTransaction()` enforces `SUM(Debits) = SUM(Credits)` per currency inside a single ACID transaction before commit, any non-zero difference triggers an automated operations alert within minutes rather than days.

### 3. Journal Entry Standardisation Framework
- Enforce a centralized `TransactionEngineService` keyed by a strongly typed `TransactionType` enum (all 20 types) so product teams cannot hand-craft ad-hoc debit/credit lines.
- Every journal entry passes through `LedgerService.postTransaction()`, which acts as the single choke point enforcing account-currency matching, positive `NUMERIC(19,4)` amounts, mandatory `created_by` metadata, and balanced per-currency legs.

### 4. NPCI UPI Settlement Reconciliation Engine
- **3-Way Deterministic Matching Algorithm:** Match internal `ledger_entries` against NPCI settlement files and partner bank statements using a composite key of `(reference_id / RRN, amount, currency, settlement_date)`.
- **Bucket Classification:** Classify every record into `MATCHED`, `TIMING_DIFFERENCE` (posted in ledger, T+1 in NPCI), `MISSING_IN_LEDGER`, or `AMOUNT_MISMATCH`, and auto-generate suspense/exception tickets for unresolved items older than T+1.

---

## Case Study 2: Revolut's Multi-Currency Rounding Incident (2019)

### 1. Concrete IEEE 754 Floating-Point Failure (JPY to KWD)
- Suppose a user converts **1,000 JPY** to **KWD** at `1 JPY = 0.0020137 KWD`, or repeatedly converts `0.1` units:
    - In IEEE 754 binary64 (`double`), `0.1 + 0.2 = 0.30000000000000004` because base-10 fractions like 1/10 have infinite repeating binary representations.
    - For `1000.0 * 0.0020137` across 2,000,000 micro-conversions, binary drift (`2.0136999999999998` vs `2.0137`) accumulates into thousands of currency units of unattributed surplus/deficit in the FX holding account.

### 2. Zero-Rounding-Error Multi-Currency Arithmetic Module
- **Database Layer:** `NUMERIC(19,4)` for all monetary amounts (`ledger_entries.amount`) and `NUMERIC(18,8)` for exchange rates and inverse rates (`exchange_rate_snapshots.rate`, `inverse_rate`). Zero `FLOAT`/`DOUBLE` columns exist anywhere in the schema.
- **Application Layer:** Java `BigDecimal` everywhere, normalized with `.setScale(4, RoundingMode.HALF_UP)` before persistence and SHA-256 hashing (`LedgerService` and `ExchangeRateService`).

### 3. Automated FX Holding Discrepancy Detection
- The Currency Exposure Report (`ExchangeRateService.getCurrencyExposureReport()`) aggregates balances per currency and converts positions to INR using the latest valid rate snapshot.
- Any residual balance in the FX Conversion Holding accounts (`1004` INR / `1005` USD) exceeding a configurable tolerance (e.g., `> 1.00 INR` or `> 0.01%` of daily FX volume) triggers an automated reconciliation alert.

### 4. End-of-Day Rounding Reconciliation Entry
- Sub-unit conversion residuals (fractions of a paisa/cent) are swept at EOD via an automated balanced journal entry:
    - Surplus residual: `DEBIT FX Conversion Holding (1004)` / `CREDIT FX Rounding Gain (4004)`
    - Deficit residual: `DEBIT FX Rounding Loss (5011)` / `CREDIT FX Conversion Holding (1004)`

---

## Case Study 3: Wirecard Missing EUR 1.9 Billion Audit Trail Failure (2020)

### 1. Preventing Backdating & Mutation via Cryptographic Hash Chaining
- Every row in `ledger_entries` is linked sequentially (`chain_seq`) to the previous entry's SHA-256 hash (`previous_hash`), serialized under `pg_advisory_xact_lock(424242)` in `LedgerService`.
- The hash covers 9 immutable fields: `previous_hash | entry_id | journal_id | account_id | entry_type | amount | currency | effective_date | created_by`.
- Backdating, inserting mid-chain, or altering even 1 paisa breaks both the row hash and every subsequent `previous_hash` link, caught by `GET /api/v1/ledger/audit/verify` (tested in `DemoApplicationTests.testAuditTrailDetectsActualDatabaseTampering`).
- PostgreSQL trigger `prevent_ledger_mutation()` (`V11`) blocks all `UPDATE` and `DELETE` operations on `POSTED`/`REVERSED` rows at the database layer.

### 2. Independent External Reconciliation Interface
- Large custodian/nostro balances must be verified via direct API/SFTP ingestion of digitally signed MT940 / camt.053 bank statements (verified via bank public keys / mTLS), never manually uploaded PDFs.
- Require dual-control maker-checker sign-off and automated checksum comparison against third-party settlement feeds before closing monthly books.

### 3. Automated Anomaly Detection
- Implemented in `ResilienceAndAuditService.detectAnomalies()` (`GET /api/ledger/audit/anomalies`), flagging large round-number entries (`amount >= threshold AND MOD(amount, 1000) = 0`) and after-hours postings (`UTC hour >= 22 OR < 5`).
- In production, extend with user-account behavioral baselines to flag actors posting to dormant or unusual GL accounts.

### 4. Tamper-Evident Regulatory Export
- Export the requested date range as chronological JSON/CSV alongside each row's `chain_seq`, `previous_hash`, `hash`, and a signed chain-head digest (`SHA-256` of the final row in the export) so regulators can independently re-verify every row offline.

---

## Case Study 4: Razorpay Concurrent Refund Vulnerability (Hypothetical)

### 1. Race Condition & Prevention Proof
- **Vulnerability:** Without locking, two concurrent refund threads both read `SUM(refunded) = 0` for a 5,000 INR payment, both pass the check, and both credit 5,000 INR (total 10,000 INR refunded).
- **Prevention in Our Architecture:**
    - Full reversals (`LedgerService.revertTransaction`) acquire a per-journal transactional advisory lock (`SELECT pg_advisory_xact_lock(hashtext('REV:' || originalJournalId))`) and atomically claim the key in `idempotency_keys` (`INSERT ... ON CONFLICT DO NOTHING`).
    - Partial refunds (`TransactionEngineService.processPartialRefund`) enforce `UNIQUE (original_journal_id, idempotency_key)` on `partial_refunds` and validate `alreadyRefunded + refundAmount <= originalAmount`, while `LedgerService.postTransaction` locks involved accounts via `SELECT ... FOR UPDATE` in sorted UUID order (`AccountRepository.findByIdWithLock`).

### 2. Concurrency Control Comparison: Pessimistic Locking vs. SERIALIZABLE
- **Pessimistic Locking (`SELECT ... FOR UPDATE` / `pg_advisory_xact_lock`):**

  SELECT pg_advisory_xact_lock(hashtext('REFUND:' || :original_journal_id));
  SELECT COALESCE(SUM(refund_amount), 0)
  FROM partial_refunds
  WHERE original_journal_id = :original_journal_id;

    - *Trade-off:* Zero serialization aborts and simple client logic, at the cost of briefly serializing concurrent requests on the same transaction/account (deadlocks prevented by locking account UUIDs in sorted order).

- **SERIALIZABLE Isolation (PostgreSQL SSI):**

  BEGIN TRANSACTION ISOLATION LEVEL SERIALIZABLE;
  -- Read total refunded and insert refund; PostgreSQL aborts one concurrent txn with SQLSTATE 40001
  COMMIT;

    - *Trade-off:* No explicit lock ordering required, but requires application-level retry loops on `40001` serialization failures and suffers higher abort rates under heavy contention.

### 3. Derived Refund Ledger Pattern
- Never store a mutable `refunded_amount` counter on the original transaction. Always derive total refunded amount directly from immutable rows:

  SELECT original_journal_id, COALESCE(SUM(refund_amount), 0) AS total_refunded
  FROM partial_refunds
  WHERE original_journal_id = :original_journal_id
  GROUP BY original_journal_id;

### 4. Refund-Ratio Circuit Breaker
- Track rolling `refund_count / total_txn_count` (or `refund_volume / gross_volume`) over a 15-minute window; if it breaches a threshold (e.g., `> 10%`), trip the circuit breaker (`ResilienceAndAuditService`) to `OPEN` (returning HTTP `503` with `Retry-After`) and alert risk operations.

---

## Case Study 5: Silicon Valley Bank Liquidity Ledger Blind Spot (2023)

### 1. Real-Time Liquidity Dashboard Architecture
- **Sub-Second Queries:** Back the dashboard with `balance_snapshots` (updated atomically inside `LedgerService.postTransaction()`) for instant cash/deposit totals, avoiding full-table `SUM()` scans over millions of rows:

  SELECT a.account_type, bs.currency, SUM(bs.balance) AS live_balance
  FROM balance_snapshots bs
  JOIN accounts a ON a.id = bs.account_id
  WHERE a.account_type IN ('Asset', 'Liability')
  GROUP BY a.account_type, bs.currency;

- Pair with event-driven Redis/read-replica streaming for executive dashboards so analytical reads never contend with write locks.

### 2. Handling 10,000 Withdrawals/Minute
- **Admission Control & Rate Limiting:** API gateway token-bucket limits per user/tier returning HTTP `429` with exponential backoff.
- **Priority Queuing & Backpressure:** Route standard withdrawals through partitioned message queues (partitioned by `account_id` to preserve ordering and avoid hot-row lock pileups), prioritizing retail/regulatory obligations over bulk sweeps.
- **Graceful Degradation:** Circuit breakers (`ResilienceAndAuditService`, wired to `/api/v1/ledger/transfer` returning `503 Service Unavailable` + `Retry-After: 30`) shed external bank-rail failures cleanly while keeping read-only balance and statement queries online.

### 3. Nightly Liquidity Stress Test Simulation
- Run an automated off-peak simulation against a read-replica/staging clone modeling 40% deposit outflow over 24 hours, verifying (a) liquid cash assets vs. projected outflow coverage ratio, (b) p99 transaction latency under peak concurrency, and (c) zero trial-balance or hash-chain breaks.

### 4. Early-Warning Outflow/Inflow Monitor
- Continuously compute `outflow_to_inflow_ratio` over rolling **1-hour, 4-hour, and 24-hour** windows from `ledger_entries` (`effective_date >= NOW() - INTERVAL '1 hour'`), escalating alerts when the ratio exceeds `1.5x` (warning) or `3.0x` (critical treasury intervention).

### 5. Regulatory Reconstruction Export
- Export date-bounded `ledger_entries` (using monthly partition pruning), `accounts` (CoA), and `exchange_rate_snapshots` in **Parquet/CSV + JSON manifest** containing the exact `asOfDate` trial balance, balance sheet (`A = L + E`), and the SHA-256 chain head hash to cryptographically prove completeness and non-tampering.