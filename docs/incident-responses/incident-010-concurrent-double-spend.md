# Incident 010: Concurrent Double-Spend (-INR 8,500 Balance)
- **Root Cause:** Two concurrent withdrawals passed the balance check before either committed (TOCTOU race condition).
- **Impact:** Direct financial loss and negative wallet balance.
- **Design Solution:** Pessimistic locking (`SELECT ... FOR UPDATE`) with deterministic UUID lock ordering in `LedgerService`, verified via concurrent JUnit 5 tests.
