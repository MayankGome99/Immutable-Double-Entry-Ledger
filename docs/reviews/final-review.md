# Final Engineering Retrospective — BED-6C Double-Entry Ledger

## 1. Final Verified Build Snapshot
- **Test Suite:** 46/46 JUnit tests passing (`DemoApplicationTests`: 7, `ResilienceAndLoadTests`: 4, `RubricComplianceTests`: 29, `ControllerAndConcurrencyTests`: 5).
- **Coverage Gate:** `mvn clean verify` passes JaCoCo 0.8.12 check (`>= 80%` minimum line coverage across all 22 analyzed classes; 93.0% line coverage (983/1,057 lines) across core `config`, `controllers`, `exception`, `services`, and `validators` packages).
- **Schema Integrity:** Flyway migrations `V1`–`V13` apply cleanly from an empty PostgreSQL 15 volume with `spring.flyway.validate-on-migrate` enabled.

## 2. What Went Well
- **Defense-in-Depth Immutability:** Combining the PostgreSQL `prevent_ledger_mutation()` trigger (`V11`) with a serialized global 9-field SHA-256 hash chain (`chain_seq` + `pg_advisory_xact_lock`) ensures tampering is both prevented at the DB layer and provably detected even if a superuser bypasses the trigger.
- **Strict Fixed-Point & Per-Currency Discipline:** Using `BigDecimal` + `NUMERIC(19,4)` everywhere and enforcing `account.currency == entry.currency` plus per-currency `SUM(Debits) == SUM(Credits)` caught subtle multi-currency bugs early (such as USD entries previously targeting INR equity `3001`).
- **Catching Brief Traps:** Auditing every formula and SQL snippet in the brief caught 8 deliberate errors—including unbalanced P2P/FX examples, `UNIQUE(idempotency_key)` breaking multi-line journals, `NOW()` in a partial index predicate, and the inverted "Debit TCS Payable" posting direction.

## 3. What Was Hardest (and How It Was Solved)
- **PostgreSQL Partitioning vs. Global Uniqueness:** Converting `ledger_entries` to monthly range partitioning (`V5`) meant any `UNIQUE` constraint on `ledger_entries` had to include `effective_date`. We solved global idempotency cleanly by moving atomic key claiming to a dedicated `idempotency_keys` table (`V7`) using `INSERT ... ON CONFLICT DO NOTHING`.
- **Closing the Trigger & Hash Chain Gaps:** Early versions reset `previous_hash` per journal and allowed `UPDATE ... SET status = 'REVERSED'` to mutate amounts simultaneously. Moving to a global sequence (`chain_seq`), a 9-field hash payload, and a hardened `V11` trigger (while reverting `V4` instead of bypassing Flyway checksums) closed both loopholes.
- **Race-Safe Partial Refunds:** Trusting client-supplied `originalAmount` and `originalFee` created both spoofing and over-refund race risks. Refactoring `processPartialRefund()` to acquire a per-journal `pg_advisory_xact_lock` and derive principal/fee directly from PostgreSQL `ledger_entries`—then proving it with a 10-thread concurrency test—resolved the vulnerability.

## 4. What I Would Change for Production Scale
- **Per-Customer Sub-Ledger Accounts & RBAC:** Replace the shared Chart of Accounts simulation accounts with dynamically provisioned per-customer liability sub-wallets and JWT/OAuth2 role-based access control (`MAKER`, `CHECKER`, `AUDITOR`).
- **Outbox Pattern & Read Replicas:** Publish committed journal events via a transactional outbox to Kafka/Redis so real-time liquidity dashboards and regulatory exports query read replicas without contending with write locks.
- **Automated Partition Lifecycle:** Schedule `scripts/manage-partitions.sql` and `scripts/archive-partition.sql` via `pg_cron` or Kubernetes CronJobs with automated `pg_dump` checksum verification to S3 Glacier.