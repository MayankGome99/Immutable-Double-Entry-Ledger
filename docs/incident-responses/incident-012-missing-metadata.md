# Incident 012 (Day 12): 340 Audit Entries Missing Creator Metadata

- **Impact:** Compliance audit finding; unable to trace which actor or system process initiated 340 financial entries.
- **Root Cause:** `created_by` was nullable in the legacy schema and omitted by internal batch jobs.
- **Detection:** Compliance audit query (`SELECT COUNT(*) FROM ledger_entries WHERE created_by IS NULL`) flagged 340 untraceable rows.
- **Fix Implemented:**
    - Enforced `created_by VARCHAR(100) NOT NULL` at the PostgreSQL schema level (`V5__partition_ledger_entries.sql`).
    - Included `created_by` inside the 9-field SHA-256 hash payload (`HashUtils.computeEntryHash`), making creator metadata cryptographically tamper-evident.
    - Added `RequestIdFilter` (`X-Request-Id`) so every API call is traceable across logs and responses, and blocked mutating `created_by` in `prevent_ledger_mutation()` (`V11`).
- **Prevention:** Database `NOT NULL` constraint + trigger protection + hash-chain inclusion guarantee no entry can exist without immutable creator attribution.
- **Proving Tests:** `DemoApplicationTests.testAuditTrailDetectsActualDatabaseTampering`, `DemoApplicationTests.testImmutabilityTriggerBlocksDelete`, `RubricComplianceTests.testImmutabilityTriggerBlocksUpdate`