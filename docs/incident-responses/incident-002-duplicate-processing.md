# Incident 002: Duplicate P2P Processing on Network Retry
- **Root Cause:** Client timeout retries re-submitted identical transfer payloads without idempotency enforcement.
- **Impact:** Customer charged twice (INR 15,000 overcharge).
- **Design Solution:** Enforced `X-Idempotency-Key` header validation, `idempotency_keys` table (V7), and `UNIQUE` constraint on `(original_journal_id, idempotency_key)` returning HTTP 409 Conflict on duplicates.
