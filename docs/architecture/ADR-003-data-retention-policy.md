# ADR-003: Data Retention, Partitioning & Archival Policy

## Status
Accepted

## Context
RBI Master Directions mandate a minimum 10-year retention period for financial records. Unpartitioned ledger tables degrade query and index performance over time.

## Decision
- **Partitioning (V5):** `ledger_entries` uses PostgreSQL native `PARTITION BY RANGE (effective_date)` with monthly partitions for partition pruning.
- **Hot Storage (0–90 days):** Active monthly partitions in primary PostgreSQL with full composite indexing.
- **Warm Storage (90 days – 7 years):** Detached/archived partitions moved to read-optimized warm tablespaces.
- **Cold Storage (7 – 10+ years):** Exported to compressed Parquet/CSV in immutable object storage (S3 Object Lock) to satisfy RBI 10-year retention rules.
