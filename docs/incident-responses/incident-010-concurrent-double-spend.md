# Incident 010: Concurrent Double-Spend (-INR 8,500 Balance)
- **Root Cause:** Two concurrent withdrawals passed check-then-act balance verification simultaneously.
- **Impact:** Negative wallet balance and direct financial loss.
- **Design Solution:** Dual concurrency controls: pessimistic row locking (`SELECT ... FOR UPDATE`) with deterministic UUID lock ordering plus PostgreSQL transactional advisory locks (`pg_advisory_xact_lock`), verified by 50 concurrent withdrawals test.
