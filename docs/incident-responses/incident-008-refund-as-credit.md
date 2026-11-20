# Incident 008: Refund Posted as New Credit (Revenue Double-Counted)
- **Root Cause:** Merchant refund was recorded as a fresh credit instead of reversing the original transaction and fee lines.
- **Impact:** Overstated P&L flagged by auditors.
- **Design Solution:** No-mutation reversal engine (`/api/ledger/reversals/{journalId}`) and `partial_refunds` table (V9) supporting `PROPORTIONAL`, `FULL`, and `NONE` fee policies while blocking over-refunds (`total_refunded <= original_amount`).
