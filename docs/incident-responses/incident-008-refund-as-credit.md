# Incident 008: Refund Posted as New Credit (Revenue Double-Counted)
- **Root Cause:** Merchant refund was recorded as a credit instead of mirroring/reversing original lines.
- **Impact:** P&L overstated; flagged by auditors.
- **Design Solution:** No-mutation reversal engine (`/api/ledger/reversals/{journalId}`) and `partial_refunds` table (V9) supporting `PROPORTIONAL`, `FULL`, and `NONE` fee policies while blocking over-refunds.
