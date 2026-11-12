# ADR-001: Technology Stack Selection

## Decision
We selected **Java 21, Spring Boot 3.x, and PostgreSQL 15** as the core technology stack for the Ledger System.

## Justification
1. **Precision:** Java's `BigDecimal` prevents the floating-point rounding errors (like IEEE 754 issues) that caused material discrepancies in real-world fintech cases.
2. **Concurrency & Atomicity:** Spring Boot's `@Transactional` annotation combined with PostgreSQL's ACID compliance ensures safe double-entry journal operations without partial commits.
3. **Immutability:** PostgreSQL supports trigger-based restrictions to enforce the no-mutation principle (append-only ledger).