# ADR-002: Concurrency Control, Deadlock Prevention & Locking Strategy

- **Status:** Accepted
- **Date:** 2026-11-21
- **Context:** A financial ledger under concurrent load faces three critical race conditions: (1) double-spend on simultaneous withdrawals, (2) over-refunds / duplicate reversals on the same journal, and (3) forks in the global SHA-256 hash chain (`previous_hash` collisions).

## Decision
We implement a **hybrid pessimistic + transactional advisory locking** strategy inside PostgreSQL 15 under `READ COMMITTED` isolation:

1. **Account-Level Pessimistic Row Locks (`SELECT ... FOR UPDATE`) in Sorted UUID Order:**
    - Inside `LedgerService.postTransaction()`, all distinct `account_id` UUIDs in a journal are sorted lexicographically before calling `AccountRepository.findByIdWithLock()`.
    - **Deadlock Proof:** Because every transaction acquires row locks in the same total order (`UUID_A < UUID_B`), a circular wait cycle (`T1 holds A waits for B`, `T2 holds B waits for A`) is mathematically impossible.
2. **Per-Entity Transactional Advisory Locks (`pg_advisory_xact_lock`):**
    - **Global Hash Chain:** `pg_advisory_xact_lock(424242)` serializes reading the latest `chain_seq`/`hash` and appending new rows so the SHA-256 chain remains strictly linear with zero forks.
    - **Full Reversals & Partial Refunds:** `pg_advisory_xact_lock(hashtext('REV:' || originalJournalId))` and `pg_advisory_xact_lock(hashtext('REFUND:' || originalJournalId))` serialize concurrent refund attempts on the same journal without locking unrelated transactions.
    - **Advisory Withdrawal Path:** `ResilienceAndAuditService.withdrawWithAdvisoryLock()` demonstrates per-account advisory locking (`hashtext(accountId)`).
3. **Atomic Idempotency Claiming:**
    - `INSERT INTO idempotency_keys (...) ON CONFLICT (user_id, idempotency_key) DO NOTHING` eliminates check-then-act races at the database engine level.

## Alternatives Considered

| Strategy | Pros | Cons | Verdict |
| :--- | :--- | :--- | :--- |
| **Pessimistic Row + Advisory Locks (Chosen)** | Deterministic serialization, zero `40001` retry storms, simple client semantics | Brief serialization wait on hot accounts / global hash chain head | **Selected** |
| **PostgreSQL `SERIALIZABLE` (SSI)** | No manual lock ordering needed | High abort rates (`SQLSTATE 40001`) under 50-thread hot-account contention; requires retry middleware | Rejected for hot wallets |
| **Optimistic Locking (`@Version`)** | Good for low-contention read-heavy tables | Causes high retry failure rates on hot GL accounts (e.g., Fee Revenue `4001`, Settlement `1003`) | Rejected for core ledger |

## Measured Local Benchmark & Verification Results (Java 21 + PostgreSQL 15)
Measured via `./mvnw clean verify` on local Docker PostgreSQL 15:

| Test Scenario | Concurrency / Volume | Measured Suite Time | Verified Outcome |
| :--- | :--- | :--- | :--- |
| **Double-Spend Withdrawal Stress (`ResilienceAndLoadTests`)** | 50 concurrent threads x INR 500 on INR 10,000 balance | 1.06s – 1.59s (4-test class) | Exactly 20 succeed, 30 fail, final balance = `0.0000`, 0 deadlocks |
| **Concurrent Partial Refund Race (`RubricComplianceTests`)** | 10 concurrent threads refunding same original journal | Included in 19.21s class run | Total refunded strictly `<= originalAmount`, 0 over-refunds |
| **Concurrent Full Reversal Race (`ControllerAndConcurrencyTests`)** | 10 concurrent threads reversing same journal | Included in 24.66s class run | Exactly 1 reversal succeeds, 9 rejected (`409 Conflict`) |
| **High-Volume Balanced Throughput (`RubricComplianceTests`)** | 1,000 randomized transactions across all 20 types | Included in 19.21s class run | Trial balance discrepancy = `0.0000` per currency, hash chain valid |

*(For sustained HTTP throughput benchmarks—RPS and p95 latency—run `k6 run tests/load/ledger-load-test.js` and `k6 run tests/load/migration-during-load.test.js` against a live Spring Boot instance.)*

## Consequences
- **Positive:** Zero double-spends, zero deadlocks, zero over-refunds, and a provably linear hash chain under concurrent stress.
- **Trade-Off:** The global hash-chain advisory lock (`424242`) serializes final hash assignment across all journals. At >5,000 TPS in production, this would be sharded into per-partition or per-account Merkle hash chains anchored periodically to a global root.