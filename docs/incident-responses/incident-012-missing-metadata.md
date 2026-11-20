# Incident 012: 340 Entries Missing Creator Metadata
- Root Cause: created_by field lacked database NOT NULL enforcement.
- Impact: Compliance audit failure; untraceable transactions.
- Design Solution: Enforced NOT NULL constraint on created_by in ledger_entries (V3) plus service-layer validation.
