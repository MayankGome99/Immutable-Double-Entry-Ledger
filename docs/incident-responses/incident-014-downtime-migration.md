# Incident 014: 4-Hour Outage During Schema Migration
- **Root Cause:** Blocking DDL statements locked the primary ledger table during business hours.
- **Impact:** Complete transaction downtime and revenue loss.
- **Design Solution:** Flyway online DDL strategy (`V6` non-blocking column + composite index, `V7`–`V9` additive tables/seeds) with documented rollback scripts.
