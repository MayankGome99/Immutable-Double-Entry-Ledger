# Incident 014: 4-Hour Outage During Schema Migration
- Root Cause: Blocking DDL locked primary ledger table during business hours.
- Impact: 4-hour downtime and revenue loss.
- Design Solution: Flyway online DDL migrations (V6 non-blocking column + composite index, V7-V9 additive tables/seeds) with rollback strategies.
