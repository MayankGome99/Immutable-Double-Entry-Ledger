#  Immutable Double-Entry Ledger

A production-style financial ledger backend built with **Java 21**, **Spring Boot 4.1.2**, **PostgreSQL 15**, **Flyway**, and **Docker**. Designed for strict double-entry accounting, cryptographic tamper detection, concurrency safety, and partitioned scalability.

## Tech Stack
- **Backend:** Java 21, Spring Boot 4.1.2, Spring Data JPA (Hibernate)
- **Database & Migrations:** PostgreSQL 15 (Dockerized), Flyway (`V1`–`V6`)
- **API Documentation:** OpenAPI 3 / Swagger UI (`springdoc-openapi`)

## Key Features
- **Double-Entry Validation:** Rejects any transaction where total debits do not equal total credits.
- **Automated P2P Transfers:** Atomic peer-to-peer fund transfers using pessimistic row locking (`SELECT ... FOR UPDATE`).
- **Immutability Triggers:** PostgreSQL triggers prevent `UPDATE` or `DELETE` on posted ledger entries.
- **Cryptographic Audit Trail:** SHA-256 hash-chaining across entries with an on-demand verification endpoint.
- **Monthly Range Partitioning:** `ledger_entries` partitioned by `effective_date` for fast queries and archival.
- **Zero-Downtime Migrations:** Versioned Flyway migrations (`V1`–`V6`) including non-blocking schema updates and composite indexing.

## How to Run Locally
1. **Start PostgreSQL via Docker:**
   ```bash
   docker compose up -d
