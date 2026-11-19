-- 1. Purane normal table ka naam badal kar 'old' kar rahe hain

ALTER TABLE ledger_entries RENAME TO ledger_entries_old;

-- 2. Naya parent table bana rahe hain jo effective_date se partition hoga

CREATE TABLE ledger_entries (
                                entry_id UUID NOT NULL,
                                journal_id UUID NOT NULL,
                                account_id UUID NOT NULL REFERENCES accounts(id),
                                entry_type VARCHAR(10) NOT NULL CHECK (entry_type IN ('DEBIT', 'CREDIT')),
                                amount NUMERIC(19,4) NOT NULL CHECK (amount > 0),
                                currency CHAR(3) NOT NULL,
                                effective_date TIMESTAMP WITH TIME ZONE NOT NULL,
                                posted_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
                                created_by VARCHAR(100) NOT NULL,
                                idempotency_key VARCHAR(128) NOT NULL,
                                reference_type VARCHAR(50),
                                reference_id UUID,
                                narrative TEXT,
                                hash CHAR(64) NOT NULL,
                                previous_hash CHAR(64),
                                metadata JSONB,
                                status VARCHAR(20) NOT NULL CHECK (status IN ('PENDING', 'POSTED', 'REVERSED')),
                                PRIMARY KEY (entry_id, effective_date),
                                UNIQUE (idempotency_key, effective_date)
) PARTITION BY RANGE (effective_date);

-- 3. Har mahine ke liye alag child tables (drawers) bana rahe hain

CREATE TABLE ledger_entries_2026_11 PARTITION OF ledger_entries
    FOR VALUES FROM ('2026-11-01 00:00:00+00') TO ('2026-12-01 00:00:00+00');

CREATE TABLE ledger_entries_2026_12 PARTITION OF ledger_entries
    FOR VALUES FROM ('2026-12-01 00:00:00+00') TO ('2027-01-01 00:00:00+00');

CREATE TABLE ledger_entries_2027_01 PARTITION OF ledger_entries
    FOR VALUES FROM ('2027-01-01 00:00:00+00') TO ('2027-02-01 00:00:00+00');

-- Safety-net drawer: agar koi aisi date aaye jo upar nahi hai, toh app crash nahi hoga

CREATE TABLE ledger_entries_default PARTITION OF ledger_entries DEFAULT;

-- 4. Purane table se saara data naye partitioned table mein copy karo

INSERT INTO ledger_entries
SELECT * FROM ledger_entries_old;

-- 5. Ab purane table ko delete (drop) kar do

DROP TABLE ledger_entries_old;

-- 6. V4 wala immutability security trigger new table pe wapas lagao

CREATE TRIGGER enforce_ledger_immutability
    BEFORE UPDATE OR DELETE ON ledger_entries
    FOR EACH ROW EXECUTE FUNCTION prevent_ledger_mutation();