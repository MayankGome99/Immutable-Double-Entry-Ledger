-- =============================================================================
-- manage-partitions.sql
-- Purpose: Pre-create the next 3 monthly partitions for ledger_entries and
--          inspect existing partition bounds, row estimates, and disk sizes.
-- Safe to run repeatedly (idempotent) via cron / pg_cron on the 1st of each month.
-- =============================================================================

DO $$
DECLARE
i               INT;
    start_month     TIMESTAMPTZ;
    end_month       TIMESTAMPTZ;
    partition_name  TEXT;
BEGIN
FOR i IN 0..3 LOOP
        start_month    := date_trunc('month', NOW() AT TIME ZONE 'UTC') + make_interval(months => i);
        end_month      := start_month + INTERVAL '1 month';
        partition_name := 'ledger_entries_' || to_char(start_month, 'YYYY_MM');

EXECUTE format(
        'CREATE TABLE IF NOT EXISTS %I PARTITION OF ledger_entries FOR VALUES FROM (%L) TO (%L);',
        partition_name,
        start_month,
        end_month
        );

RAISE NOTICE 'Ensured partition % [% -> %)', partition_name, start_month, end_month;
END LOOP;
END $$;

-- Inspect all currently attached partitions of ledger_entries
SELECT
    c.relname                                    AS partition_name,
    pg_get_expr(c.relpartbound, c.oid)           AS partition_bounds,
    c.reltuples::BIGINT                          AS estimated_rows,
    pg_size_pretty(pg_total_relation_size(c.oid)) AS total_size
FROM pg_inherits i
         JOIN pg_class c ON c.oid = i.inhrelid
WHERE i.inhparent = 'ledger_entries'::regclass
ORDER BY c.relname;