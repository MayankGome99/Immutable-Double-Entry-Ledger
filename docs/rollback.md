# Flyway Migration Rollback Strategy (V1–V13)

> **Production Ledger Principle:** Already-applied migrations (`V1`–`V13`) are never edited in place (`spring.flyway.validate-on-migrate` remains enabled). In production, schema issues are resolved via forward-only compensating migrations (`V14+`) to preserve audit history. The reverse SQL below documents the exact rollback path for staging, CI, or emergency pre-cutover recovery.

| Version | Migration Summary | Rollback SQL (Reverse Order) | Data Loss Risk |
| :--- | :--- | :--- | :--- |
| **V13** | Add FX rounding accounts (`4005`, `5012`) | `DELETE FROM accounts WHERE account_code IN ('4005', '5012');` | None (only if unused) |
| **V12** | Add `balance_snapshots`, `fx_revaluation_state`, USD equity `3002`, FX gain/loss `4004`/`5011` | `DROP TABLE IF EXISTS balance_snapshots; DROP TABLE IF EXISTS fx_revaluation_state; DELETE FROM accounts WHERE account_code IN ('3002', '4004', '5011');` | Drops cached snapshots & revaluation cursor |
| **V11** | Harden `prevent_ledger_mutation()` immutability trigger | Re-apply prior `V4` trigger function definition (`CREATE OR REPLACE FUNCTION prevent_ledger_mutation() ...`) | None (reduces protection; not recommended) |
| **V10** | Add `chain_seq` sequence/column for global hash chain | `ALTER TABLE ledger_entries DROP COLUMN IF EXISTS chain_seq; DROP SEQUENCE IF EXISTS ledger_entries_chain_seq_seq;` | Loses global chain ordering |
| **V9** | Add `partial_refunds` table with unique `(original_journal_id, idempotency_key)` | `DROP TABLE IF EXISTS partial_refunds;` | High (drops refund audit trail) |
| **V8** | Seed additional Chart of Accounts & FX rate snapshots | `DELETE FROM exchange_rate_snapshots WHERE source = 'SEED';` (and remove V8-seeded accounts) | Medium |
| **V7** | Add `exchange_rate_snapshots`, `idempotency_keys`, `reversals` | `DROP TABLE IF EXISTS reversals; DROP TABLE IF EXISTS idempotency_keys; DROP TABLE IF EXISTS exchange_rate_snapshots;` | High |
| **V6** | Online add `transaction_channel` column & composite index `idx_ledger_entries_account_date` | `DROP INDEX IF EXISTS idx_ledger_entries_account_date; ALTER TABLE ledger_entries DROP COLUMN IF EXISTS transaction_channel;` | Low (metadata column only) |
| **V5** | Monthly range partitioning on `ledger_entries (effective_date)` & `created_by NOT NULL` | Restore pre-partition table from `pg_dump` snapshot taken prior to cutover (partition conversion is not reversible in-place without table rewrite). | Requires snapshot restore |
| **V4** | Initial immutability trigger on `ledger_entries` | `DROP TRIGGER IF EXISTS trg_prevent_ledger_mutation ON ledger_entries; DROP FUNCTION IF EXISTS prevent_ledger_mutation();` | None (disables DB guard) |
| **V3** | Seed core Chart of Accounts (`1001`–`5010`) | `DELETE FROM accounts WHERE account_code IN (...);` | High if referenced by entries |
| **V2** | Create `ledger_entries` table & constraints | `DROP TABLE IF EXISTS ledger_entries CASCADE;` | Destructive |
| **V1** | Create `accounts` table | `DROP TABLE IF EXISTS accounts CASCADE;` | Destructive |

## Standard Rollback Execution Procedure

1. **Before deploying any new migration (`V14+`):** Capture a logical backup (`pg_dump -Fc ledger_db > backup_pre_v14.dump`).
2. **Preferred recovery (Expand-and-Contract):** Deploy a forward migration (`V14`) that reverts application-visible behavior without dropping historical ledger rows.
3. **Staging / Local Clean Reset:**

   docker compose down -v
   docker compose up -d db
   ./mvnw clean verify