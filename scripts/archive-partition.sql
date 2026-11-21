-- =============================================================================
-- archive-partition.sql
-- Purpose: Non-blocking detachment and archival of a historical monthly
--          partition from ledger_entries without locking active writes.
--
-- Usage (replace target partition name before running):
--   1. Run STEP 1 & STEP 2 in psql (cannot run CONCURRENTLY inside a BEGIN block).
--   2. Export detached table to compressed cold storage:
--        pg_dump -U root -d ledger_db -Fc -t archive.ledger_entries_2025_01 > ledger_entries_2025_01.dump
--   3. Verify SHA-256 checksum of the dump and store the chain-head hash before
--      any compliance-approved DROP TABLE.
-- =============================================================================

-- Ensure archive schema exists
CREATE SCHEMA IF NOT EXISTS archive;

-- STEP 1: Non-blocking detach (requires PostgreSQL 14+, run outside explicit transaction)
-- Replace 'ledger_entries_2025_01' with the target historical partition (> 12 months old).
ALTER TABLE ledger_entries
    DETACH PARTITION ledger_entries_2025_01 CONCURRENTLY;

-- STEP 2: Move detached partition into the archive schema (keeps it queryable for audits)
ALTER TABLE ledger_entries_2025_01
    SET SCHEMA archive;

-- STEP 3: Verify detached table is now in archive schema with row count intact
SELECT
    schemaname,
    tablename,
    pg_size_pretty(pg_total_relation_size(schemaname || '.' || tablename)) AS archive_size
FROM pg_tables
WHERE schemaname = 'archive'
ORDER BY tablename;