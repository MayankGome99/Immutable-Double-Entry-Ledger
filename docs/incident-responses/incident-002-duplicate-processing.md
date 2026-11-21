# Incident 002 (Day 2): Duplicate P2P Transfer on Network Retry

- **Impact:** Customer charged INR 15,000 twice after a client timeout triggered an automatic retry; escalated to Nodal Officer.
- **Root Cause:** The legacy engine lacked atomic idempotency enforcement and relied on non-atomic check-then-act lookups, allowing two concurrent or retried requests with the same intent to both post.
- **Detection:** Duplicate debit lines with identical amount, account, and near-identical timestamps flagged during customer escalation and daily reconciliation.
- **Fix Implemented:**
    - Created `idempotency_keys` (`V7__add_fx_rates_idempotency_and_reversals.sql`) with `PRIMARY KEY (user_id, idempotency_key)` and 24-hour `expires_at`.
    - Updated `LedgerService.postTransaction()` to claim keys atomically via `INSERT INTO idempotency_keys ... ON CONFLICT (user_id, idempotency_key) DO NOTHING`, rejecting duplicates with HTTP `409 Conflict` (`IllegalStateException`).
- **Prevention:** Every state-mutating request accepts `X-Idempotency-Key`; duplicate or concurrent retries are blocked atomically inside PostgreSQL before any ledger line is inserted.
- **Proving Test:** `DemoApplicationTests.testDuplicateIdempotencyKeyIsRejected`