# Automated Test & Coverage Summary

- Test Suite: JUnit 5 + Spring Boot Integration Tests (./mvnw test)
- Total Automated Tests: 16 Passed, 0 Failed, 0 Errors (BUILD SUCCESS)
- Suites Covered:
  1. DemoApplicationTests (6 tests): Core double-entry, immutability trigger, P2P concurrency, SHA-256 hash chain, reporting, reversals.
  2. RubricComplianceTests (6 tests): All 20 transaction types, stale FX rate rejection, USD->INR conversion & exposure report, 3 partial refund fee policies, over-refund & duplicate idempotency blocking, 500-transaction randomized stress test.
  3. ResilienceAndLoadTests (4 tests): Unrealised FX P&L revaluation, anomaly detection, circuit breaker state machine (CLOSED -> OPEN -> HALF_OPEN -> CLOSED), and 50 concurrent withdrawals load test (exact 20 succeed / 30 fail / 0 negative balance).
