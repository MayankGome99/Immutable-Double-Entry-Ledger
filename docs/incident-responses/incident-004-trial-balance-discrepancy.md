# Incident 004: Monthly Trial Balance Discrepancy (INR 2,47,000)
- **Root Cause:** Unbalanced entries and missing automated checks caused debit/credit drift.
- **Impact:** Delayed monthly book closure and RBI notice risk.
- **Design Solution:** Pre-commit `SUM(debits) == SUM(credits)` invariant in `LedgerService` (HTTP 422), SHA-256 hash chaining, and real-time `/api/ledger/trial-balance` + `/api/ledger/audit` endpoints.
