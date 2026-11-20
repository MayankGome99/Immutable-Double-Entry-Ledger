# Incident 006: Stale Exchange Rate Applied (48 Hours Old)
- **Root Cause:** FX conversion used an unvalidated cached rate without checking expiry.
- **Impact:** Customer lost INR 4,200 on USD deposit conversion.
- **Design Solution:** Immutable `exchange_rate_snapshots` table (V7) with `valid_from` and `valid_until` windows. `ExchangeRateService.getValidRate()` rejects expired rates with HTTP 422 (verified by `testStaleExchangeRateIsRejected`).
