# Incident 002: Duplicate P2P Processing on Network Retry
- **Root Cause:** Client retries resubmitted transfer payloads without idempotency enforcement.
- **Impact:** Customer double-charged (INR 15,000 overcharge).
- **Design Solution:** Enforced `X-Idempotency-Key` header validation, `idempotency_keys` table (V7), and `UNIQUE` constraint on `(original_journal_id, idempotency_key)` returning HTTP 409 Conflict on duplicates.
