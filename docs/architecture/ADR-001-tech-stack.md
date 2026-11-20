# ADR-001: Technology Stack Selection

## Status
Accepted

## Context
NovaPay requires an immutable double-entry financial ledger capable of strict ACID compliance, exact decimal arithmetic, native partitioning, and high-concurrency transaction processing.

## Decision
- **Language & Framework:** Java 21 + Spring Boot 4.1.2 (strong static typing, mature Spring Data JPA & `@Transactional` boundaries, native `BigDecimal` support to eliminate IEEE 754 floating-point errors).
- **Database:** PostgreSQL 15 (mandatory; provides `NUMERIC(19,4)`, PL/pgSQL immutability triggers, `SELECT ... FOR UPDATE` row locking, and native range partitioning).
- **Migrations:** Flyway (versioned SQL scripts V1–V9 with rollback documentation).
- **API Docs & Testing:** OpenAPI 3 / Swagger UI (`springdoc-openapi`) and JUnit 5 integration tests.
- **Containerization:** Docker + Docker Compose.

## Consequences
Strict compile-time type safety and database-enforced invariants reduce accounting bugs at the cost of slightly higher JVM memory footprint compared to Node.js.
