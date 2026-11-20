# ADR-002: Concurrency Control & Double-Spend Prevention

## Status
Accepted

## Context
Under concurrent withdrawal or P2P transfer load, check-then-act (TOCTOU) race conditions allow two threads to read the same balance and both succeed, causing negative balances (double-spend).

## Decision
1. **Pessimistic Row-Level Locking (`SELECT ... FOR UPDATE`):** Used on account rows before deriving balances and posting debit entries.
2. **Deterministic UUID Lock Ordering:** Multi-account transfers sort account UUIDs lexicographically before locking to prevent circular wait deadlocks.
3. **Database Unique Constraints for Idempotency:** `UNIQUE (original_journal_id, idempotency_key)` on `partial_refunds` and `idempotency_keys` prevent concurrent duplicate execution.

## Verification
Verified via JUnit 5 concurrency stress tests (`DemoApplicationTests` & `RubricComplianceTests`) with zero negative balances and zero deadlocks.
