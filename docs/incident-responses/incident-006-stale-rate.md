# Incident 006 (Day 6): USD Deposit Converted at Stale Exchange Rate (48 Hours Old)

- **Impact:** Customer received INR 4,200 less than market rate; triggered social media escalation and compliance concern.
- **Root Cause:** FX conversion fetched the latest stored rate without checking whether its validity window had expired, and nightly revaluation previously used a hardcoded baseline rate (`83.00`).
- **Detection:** Customer rate dispute compared applied rate against RBI reference rate for the transaction timestamp.
- **Fix Implemented:**
    - Created immutable `exchange_rate_snapshots` (`V7`) with `NUMERIC(18,8)` precision, `captured_at`, `valid_from`, and `valid_until`.
    - `ExchangeRateService.getValidRate()` rejects any snapshot where `ZonedDateTime.now().isAfter(validUntil)` with `"Stale exchange rate rejected"`.
    - Replaced hardcoded revaluation baseline with persistent `fx_revaluation_state.last_revalued_rate` (`V12`) executed via `FxRevaluationScheduler` so only incremental rate deltas post to `4004`/`5011`.
- **Prevention:** Expired FX snapshots can never be applied; every FX journal references a valid `snapshot_id`.
- **Proving Tests:** `RubricComplianceTests.testStaleExchangeRateIsRejected`, `ResilienceAndLoadTests.testUnrealisedFxRevaluationProducesBalancedEntries`