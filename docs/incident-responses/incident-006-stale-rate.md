# Incident 006: Stale Exchange Rate Applied (48 Hours Old)
- **Root Cause:** FX conversion applied an expired cached rate without checking validity windows.
- **Impact:** Customer received INR 4,200 less than market rate.
- **Design Solution:** Immutable `exchange_rate_snapshots` table (V7) with `valid_from`/`valid_until` windows. `ExchangeRateService.getValidRate()` rejects expired rates with HTTP 422 (`testStaleExchangeRateIsRejected`).
