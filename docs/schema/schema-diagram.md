# Database Schema Diagram (PostgreSQL 15)

Core Tables & Relationships:
1. accounts (id PK, account_code UK, account_name, account_type, sub_type, currency)
2. ledger_entries (entry_id PK, journal_id, account_id FK -> accounts.id, entry_type, amount NUMERIC(19,4), currency CHAR(3), effective_date PK, posted_at, created_by NOT NULL, hash CHAR(64), previous_hash CHAR(64)) [Partitioned by RANGE on effective_date]
3. exchange_rate_snapshots (snapshot_id PK, base_currency, quote_currency, rate NUMERIC(18,8), inverse_rate NUMERIC(18,8), source, valid_from, valid_until)
4. idempotency_keys (idempotency_key PK, user_id, endpoint, status, expires_at)
5. reversals (reversal_id PK, original_journal_id, reversal_journal_id, idempotency_key UK)
6. partial_refunds (refund_id PK, original_journal_id, refund_journal_id, idempotency_key UK, original_amount, refund_amount, fee_refunded, fee_policy)
