# Part C: Real-World Case Study Analyses

## Case Study 1: Paytm Payments Bank Ledger Reconciliation Crisis (2023–2024)

### 1. Schema Design for 10 Million Transactions/Day
- **Native Range Partitioning:** `ledger_entries` is range-partitioned monthly by `effective_date` (`V5__partition_ledger_entries.sql`), enabling PostgreSQL partition pruning so EOD queries scan only the active month's partition.
- **Composite Indexing:** `idx_ledger_entries_account_date (account_id, effective_date DESC)` (`V6__zero_downtime_add_column_and_index.sql`) supports fast statement and reconciliation queries without full table scans.
- **Materialized Balance Snapshots:** `balance_snapshots` (`V12`) caches account balances per commit for O(1) reads while the ledger remains strictly append-only.
- **Batch Processing:** High-volume micro-transactions (UPI/cashback) should be ingested via worker queues partitioned by `account_id` hash to eliminate cross-worker lock contention.

### 2. Automated Near-Real-Time Trial Balance Discrepancy Detection
- Run `GET /api/v1/ledger/trial-balance?asOfDate=...` on a 5-minute schedule and immediately at EOD cut-off.
- Because `LedgerService.postTransaction()` enforces `SUM(Debits) = SUM(Credits)` per currency inside a single ACID transaction before commit, any non-zero discrepancy immediately triggers an automated alert.

### 3. Journal Entry Standardisation Framework
- Enforce a centralized `TransactionEngineService` + `TransactionValidator` keyed by the `TransactionType` enum (all 20 types) so product teams cannot hand-craft ad-hoc debit/credit legs.
- Every journal passes through `LedgerService.postTransaction()`, enforcing account-currency matching, positive `NUMERIC(19,4)` amounts, mandatory `created_by` metadata, and balanced per-currency legs.

### 4. NPCI UPI Settlement Reconciliation Engine
- **3-Way Deterministic Matching:** Match internal `ledger_entries`, NPCI settlement files, and partner bank statements using `(reference_id / RRN, amount, currency, settlement_date)`.
- **Exception Buckets:** Classify records into `MATCHED`, `TIMING_DIFFERENCE` (T+1 settlement), `MISSING_IN_LEDGER`, or `AMOUNT_MISMATCH`, generating exception tickets for unresolved items past T+1.

---

## Case Study 2: Revolut's Multi-Currency Rounding Incident (2019)

### 1. Concrete IEEE 754 Floating-Point Failure (JPY to KWD)
- Converting **1,000 JPY** to **KWD** at `1 JPY = 0.0020137 KWD` across **2,000,000** micro-conversions:
    - Exact decimal value per conversion: `1000 * 0.0020137 = 2.0137 KWD` (total over 2,000,000 conversions = `4,027,400.0000 KWD`).
    - Java `double` (IEEE 754 binary64) cannot represent `0.0020137` or `2.0137` exactly in base-2, so repeated addition accumulates floating-point drift across millions of transactions.
    - **Actual Java 21 JVM Output** *(run the `jshell` command in Phase 4 Step 3 and paste your exact terminal output below)*:

      [PASTE ACTUAL JAVA 21 OUTPUT HERE FROM PHASE 4 STEP 3]

### 2. Zero-Rounding-Error Multi-Currency Arithmetic Module
- **Database Layer:** `NUMERIC(19,4)` for monetary amounts (`ledger_entries.amount`) and `NUMERIC(18,8)` for exchange rates (`exchange_rate_snapshots.rate`, `inverse_rate`). Zero `FLOAT`/`DOUBLE` columns exist.
- **Application Layer:** Java `BigDecimal` everywhere, normalized with `.setScale(4, RoundingMode.HALF_UP)` before persistence and SHA-256 hashing (`LedgerService`, `ExchangeRateService`).

### 3. Automated FX Holding Discrepancy Detection
- `ExchangeRateService.getCurrencyExposureReport()` computes net position per currency (`Assets - Liabilities`, excluding transit account `1005`) and converts to INR using `getValidRate()`.
- Any unexplained residual in FX Holding (`1004`) above a configured threshold (e.g., `> 1.00 INR` or `> 0.01%` of daily FX volume) triggers an operations alert.

### 4. End-of-Day Rounding Reconciliation Entry
- Sub-unit conversion residuals are swept at EOD using dedicated rounding accounts (`V13__add_rounding_accounts.sql`), kept strictly separate from unrealised FX revaluation accounts (`4004` / `5011`):
    - Surplus residual: `DEBIT FX Conversion Holding (1004)` / `CREDIT FX Rounding Gain (4005)`
    - Deficit residual: `DEBIT FX Rounding Loss (5012)` / `CREDIT FX Conversion Holding (1004)`

---

## Case Study 3: Wirecard Missing EUR 1.9 Billion Audit Trail Failure (2020)

### 1. Preventing Backdating & Mutation via Cryptographic Hash Chaining
- Every row in `ledger_entries` links sequentially (`chain_seq`) to the prior entry's SHA-256 hash (`previous_hash`), serialized under `pg_advisory_xact_lock(424242)` in `LedgerService`.
- The hash covers 9 immutable fields: `previous_hash | entry_id | journal_id | account_id | entry_type | amount | currency | effective_date | created_by`.
- Modifying or backdating any row breaks both the row hash and downstream chain links, caught by `GET /api/v1/ledger/audit/verify` (verified by `DemoApplicationTests.testAuditTrailDetectsActualDatabaseTampering`).
- PostgreSQL trigger `prevent_ledger_mutation()` (`V11`) blocks `UPDATE` and `DELETE` on `POSTED`/`REVERSED` entries at the DB layer.

### 2. Independent External Reconciliation Interface
- Verify large custodian balances via direct API/SFTP ingestion of cryptographically signed MT940 / camt.053 bank statements (mTLS + bank signature verification), never manual PDFs, with dual-control maker-checker approval.

### 3. Automated Anomaly Detection
- Implemented in `ResilienceAndAuditService.detectAnomalies()` (`GET /api/v1/ledger/audit/anomalies`), flagging large round-number entries (`amount >= threshold AND MOD(amount, 1000) = 0`) and after-hours postings (`UTC hour >= 22 OR < 5`).

### 4. Tamper-Evident Regulatory Export
- Export date-bounded `ledger_entries` with `chain_seq`, `previous_hash`, `hash`, and a signed chain-head SHA-256 digest so regulators can verify every row offline.

---

## Case Study 4: Razorpay Concurrent Refund Vulnerability (Hypothetical)

### 1. Race Condition & Prevention Proof
- **Vulnerability:** Without locking, two concurrent refund requests both read `SUM(refunded) = 0` for a 5,000 INR payment and both credit 5,000 INR (10,000 INR total refunded).
- **Prevention in Code:**
    - `TransactionEngineService.processPartialRefund()` acquires a per-journal transactional advisory lock (`SELECT pg_advisory_xact_lock(hashtext('REFUND:' || originalJournalId))`), loads and validates the real original journal from PostgreSQL (rejecting non-existent or reversal journals and ignoring client-supplied amounts), checks `SUM(refund_amount)` under lock, and enforces `UNIQUE (original_journal_id, idempotency_key)` on `partial_refunds`.
    - Proven by the 10-thread concurrent partial refund test in `RubricComplianceTests` and the 10-thread concurrent full reversal test in `ControllerAndConcurrencyTests`.

### 2. Concurrency Control Comparison: Pessimistic Locking vs. SERIALIZABLE
- **Pessimistic Locking (`SELECT ... FOR UPDATE` / `pg_advisory_xact_lock`):**

  SELECT pg_advisory_xact_lock(hashtext('REFUND:' || :original_journal_id));
  SELECT COALESCE(SUM(refund_amount), 0)
  FROM partial_refunds
  WHERE original_journal_id = :original_journal_id;

    - *Trade-off:* Deterministic serialization with zero retry storms; deadlocks prevented by locking account UUIDs in sorted order.

- **SERIALIZABLE Isolation (PostgreSQL SSI):**

  BEGIN TRANSACTION ISOLATION LEVEL SERIALIZABLE;
  -- Read refunded total and insert refund; PostgreSQL aborts conflicting txn with SQLSTATE 40001
  COMMIT;

    - *Trade-off:* No explicit lock ordering needed, but requires application retry loops on `40001` and suffers higher abort rates under contention.

### 3. Derived Refund Ledger Pattern
- Total refunded amount is always derived from immutable rows, never a mutable counter:

  SELECT original_journal_id, COALESCE(SUM(refund_amount), 0) AS total_refunded
  FROM partial_refunds
  WHERE original_journal_id = :original_journal_id
  GROUP BY original_journal_id;

### 4. Refund-Ratio Circuit Breaker
- Monitor rolling `refund_volume / gross_volume` over 15-minute windows; if breached (e.g., `> 10%`), trip the circuit breaker (`ResilienceAndAuditService`) to `OPEN`, returning HTTP `503` with `Retry-After` and alerting risk ops.

---

## Case Study 5: Silicon Valley Bank Liquidity Ledger Blind Spot (2023)

### 1. Real-Time Liquidity Dashboard Architecture
- Query `balance_snapshots` (updated atomically inside `LedgerService.postTransaction()`) for sub-second live liquidity totals instead of scanning millions of historical rows:

  SELECT a.account_type, bs.currency, SUM(bs.balance) AS live_balance
  FROM balance_snapshots bs
  JOIN accounts a ON a.id = bs.account_id
  WHERE a.account_type IN ('Asset', 'Liability')
  GROUP BY a.account_type, bs.currency;

- Pair with read replicas / event-driven cache updates so dashboard reads never contend with transactional write locks.

### 2. Handling 10,000 Withdrawals/Minute
- **Rate Limiting:** API gateway token-bucket limits returning HTTP `429` with exponential backoff.
- **Partitioned Queues:** Route withdrawals by `account_id` hash to avoid hot-row lock contention, prioritizing regulatory/retail obligations.
- **Graceful Degradation:** Circuit breakers (`BANK_TRANSFER_API`) return HTTP `503` + `Retry-After: 30` when downstream rails fail, while keeping read-only statement/balance queries online.

### 3. Nightly Liquidity Stress Test Simulation
- Run an off-peak simulation on a staging replica modeling 40% deposit outflow in 24 hours, validating liquid cash coverage, p99 latency, and zero trial-balance or hash-chain breaks.

### 4. Early-Warning Outflow/Inflow Monitor
- Compute rolling 1-hour, 4-hour, and 24-hour `outflow / inflow` ratios from `ledger_entries` (`effective_date >= NOW() - INTERVAL '1 hour'`), escalating alerts at `1.5x` (warning) and `3.0x` (critical).

### 5. Regulatory Reconstruction Export
- Export date-bounded `ledger_entries` (using monthly partition pruning), `accounts`, and `exchange_rate_snapshots` in CSV/Parquet plus a JSON manifest containing the `asOfDate` trial balance, balance sheet (`A = L + E`), and SHA-256 chain head digest.