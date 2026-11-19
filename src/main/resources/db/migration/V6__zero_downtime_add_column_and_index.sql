-- Day 14: Zero-Downtime Schema Evolution (Online DDL)

-- 1. Non-blocking column addition: PostgreSQL metadata-only update karta hai, table lock nahi hota

ALTER TABLE ledger_entries
    ADD COLUMN IF NOT EXISTS transaction_channel VARCHAR(30) DEFAULT 'API';

-- 2. Passbook & Statement queries ko fast karne ke liye composite index
-- (Partitioned table pe ye automatically saare monthly child partitions pe lag jata hai)

CREATE INDEX IF NOT EXISTS idx_ledger_entries_account_date
    ON ledger_entries (account_id, effective_date DESC);

-- ============================================================
-- ROLLBACK STRATEGY (Agar kabhi is migration ko undo karna pade):
-- DROP INDEX IF EXISTS idx_ledger_entries_account_date;
-- ALTER TABLE ledger_entries DROP COLUMN IF EXISTS transaction_channel;
-- ============================================================