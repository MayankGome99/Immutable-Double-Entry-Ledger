# ADR-004: Zero-Downtime Schema Migration Strategy

## Status
Accepted

## Context
Legacy migrations caused 4-hour maintenance outages (Incident Day 14). Core banking ledgers must evolve without blocking live traffic.

## Decision
- Use Flyway sequential versioned migrations (`V1` through `V9`).
- Apply online DDL patterns only: non-blocking `ALTER TABLE ... ADD COLUMN` (nullable or fast default in PostgreSQL 15+), additive tables (`exchange_rate_snapshots`, `partial_refunds`), and composite indexes (`V6`).
- Avoid `ALTER COLUMN TYPE` table rewrites.
- Every migration file includes a documented rollback strategy.
