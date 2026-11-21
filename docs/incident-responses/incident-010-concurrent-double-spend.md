# Incident 010 (Day 10): Concurrent Withdrawals Caused Negative Balance (-INR 8,500)

- **Impact:** Direct financial loss from double-spend when two simultaneous withdrawals both passed balance validation.
- **Root Cause:** Classic TOCTOU (check-then-act) race condition: two threads read the same positive balance concurrently before either transaction committed.
- **Detection:** Negative balance discovered on customer wallet during end-of-day reconciliation.
- **Fix Implemented:**
    - Strategy 1 (Pessimistic Locking): `LedgerService.postTransaction()` acquires `SELECT ... FOR UPDATE` (`AccountRepository.findByIdWithLock`) in deterministic sorted UUID order to prevent both double-spend and deadlocks.
    - Strategy 2 (Advisory Locking): `ResilienceAndAuditService.withdrawWithAdvisoryLock()` acquires `pg_advisory_xact_lock(hashtext(accountId))` before reading balance and posting.
    - Added per-currency sufficiency checks before debiting Liabilities or crediting Assets.
- **Prevention:** Concurrent mutations against the same account are strictly serialized inside PostgreSQL; k6 load script (`tests/load/ledger-load-test.js`) validates concurrent throughput.
- **Proving Test:** `ResilienceAndLoadTests.test50ConcurrentWithdrawalsPreventDoubleSpend` (50 concurrent INR 500 withdrawals on INR 10,000: exactly 20 succeed, 30 fail, final balance = 0)