# Incident 004 (Day 4): Trial Balance Discrepancy (INR 2,47,000)

- **Impact:** Finance could not close monthly books; regulatory filing delayed with RBI notice risk.
- **Root Cause:** Unbalanced journal legs, mixed-currency aggregation (summing USD and INR together), and uniform `DEBIT - CREDIT` sign calculation across Credit-normal accounts produced false or undetected imbalances.
- **Detection:** End-of-day trial balance check reported non-zero net difference across the Chart of Accounts.
- **Fix Implemented:**
    - Enforced pre-commit per-currency `SUM(Debits) == SUM(Credits)` and `entry.currency == account.currency` inside `LedgerService.postTransaction()`.
    - Updated `LedgerEntryRepository.generateTrialBalanceAsOf()` to group by `TRIM(e.currency)` and apply account-type normal balance signs, exposed as labelled JSON with a per-currency `balanced` flag on `GET /api/v1/ledger/trial-balance`.
    - Added global SHA-256 hash chain verification (`GET /api/v1/ledger/audit/verify`) to rule out post-commit corruption.
- **Prevention:** Atomic ACID transactions make committing an unbalanced journal impossible; automated trial-balance and hash-chain checks run continuously.
- **Proving Tests:** `DemoApplicationTests.testUnbalancedTransactionIsRejected`, `DemoApplicationTests.testCurrencyMismatchIsRejected`, `RubricComplianceTests.test1000RandomizedTransactionsAcrossAll20Types`