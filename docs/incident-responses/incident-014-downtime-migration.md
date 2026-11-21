# Incident 014 (Day 14): 4-Hour Outage During Schema Migration

- **Impact:** Full transaction outage during a maintenance window, causing revenue loss and customer distrust.
- **Root Cause:** Blocking DDL (`ALTER TABLE ... ALTER TYPE` / unversioned manual table rewrites) acquired `ACCESS EXCLUSIVE` locks across the ledger table during live traffic.
- **Detection:** Health checks and transaction APIs timed out immediately after maintenance DDL began.
- **Fix Implemented:**
    - Adopted forward-only, checksum-validated Flyway migrations (`V1`–`V13`) with `spring.flyway.validate-on-migrate` enabled (never bypassed).
    - Demonstrated non-blocking online DDL in `V6__zero_downtime_add_column_and_index.sql` (`ALTER TABLE ledger_entries ADD COLUMN IF NOT EXISTS transaction_channel VARCHAR(30) DEFAULT 'API'`, metadata-only in PostgreSQL 11+) and documented rollback SQL in every migration (`V6`–`V13`).
    - Partitioned `ledger_entries` monthly (`V5`) so future partition creation and archival detachments operate without locking active partitions.
- **Prevention:** All schema changes follow expand-and-contract online DDL rules, are versioned as `V13+` without editing applied migrations, and are verified against a fresh PostgreSQL 15 container before release.
- **Proving Verification:** Clean Flyway `V1`–`V13` startup and `45/45` passing integration tests via `docker compose down -v && docker compose up -d db && .\mvnw clean verify`