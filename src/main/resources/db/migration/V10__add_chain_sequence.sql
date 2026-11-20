CREATE SEQUENCE IF NOT EXISTS ledger_entries_chain_seq;

ALTER TABLE ledger_entries
    ADD COLUMN IF NOT EXISTS chain_seq BIGINT NOT NULL DEFAULT nextval('ledger_entries_chain_seq');

CREATE INDEX IF NOT EXISTS idx_ledger_entries_chain_seq ON ledger_entries(chain_seq);