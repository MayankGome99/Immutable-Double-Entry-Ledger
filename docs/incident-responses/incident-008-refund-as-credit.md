# Incident 008 (Day 8): Merchant Refund Processed as New Credit Instead of Reversal

- **Impact:** Revenue and gross volume were double-counted; P&L was overstated and flagged by external auditors.
- **Root Cause:** Refunds were booked as ad-hoc credit entries without referencing or reading the original journal, allowing both over-refunds and concurrent double-refund race conditions.
- **Detection:** Auditor reconciliation between gross merchant revenue and net settlement totals uncovered unreversed fee revenue.
- **Fix Implemented:**
    - `LedgerService.revertTransaction()` posts exact mirror entries referencing `original_journal_id` in `reversals` (`V7`) under a per-journal advisory lock (`pg_advisory_xact_lock`), leaving original entries untouched.
    - `TransactionEngineService.processPartialRefund()` locks per `originalJournalId`, derives original principal and fee directly from DB `ledger_entries` (rejecting non-existent or reversal journals), enforces `alreadyRefunded + refundAmount <= originalAmount`, and supports `PROPORTIONAL`, `FULL`, and `NONE` fee policies.
- **Prevention:** No-mutation rule enforced by `prevent_ledger_mutation()` trigger (`V11`); all refunds must reference a valid posted journal and pass per-journal locking + `UNIQUE (original_journal_id, idempotency_key)`.
- **Proving Tests:** `RubricComplianceTests.testPartialRefundFeePolicies`, `RubricComplianceTests.testOverRefundAndDuplicateIdempotencyBlocked`, plus the 10-thread concurrent partial refund test in `RubricComplianceTests` and 10-thread concurrent reversal test in `ControllerAndConcurrencyTests`