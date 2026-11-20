# Final Project Review & Retrospective

## Summary
Implemented a production-grade Immutable Double-Entry Financial Ledger System using Java 21, Spring Boot 4.1.2, PostgreSQL 15, Flyway (V1–V9), Docker, and JUnit 5.

## Verification Results
- **Automated Test Suite:** 12/12 JUnit 5 integration & stress tests passing (`BUILD SUCCESS`).
- **20 Transaction Types:** All 20 types implemented in `TransactionEngineService` and verified across 500 randomized transactions with 0.0000 trial balance variance.
- **Immutability:** Enforced via PostgreSQL `BEFORE UPDATE OR DELETE` trigger (`V4`) + SHA-256 hash chaining.
- **Multi-Currency & Partial Refunds:** Stale rate rejection, 0.5% FX markup, Currency Exposure Report, and 3 fee refund policies (`PROPORTIONAL`, `FULL`, `NONE`) with over-refund prevention all verified.
