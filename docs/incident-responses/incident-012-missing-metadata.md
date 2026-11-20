# Incident 012: 340 Entries Missing Creator Metadata
- **Root Cause:** `created_by` was nullable and unchecked at the API/service layer.
- **Impact:** Compliance audit finding; unattributable entries.
- **Design Solution:** Database `NOT NULL` constraint on `created_by` in `ledger_entries` (V3) plus service-layer actor enforcement.
