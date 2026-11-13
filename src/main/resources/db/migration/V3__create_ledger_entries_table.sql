CREATE TABLE ledger_entries (
                                entry_id UUID PRIMARY KEY,
                                journal_id UUID NOT NULL,
                                account_id UUID NOT NULL REFERENCES accounts(id),
                                entry_type VARCHAR(10) NOT NULL CHECK (entry_type IN ('DEBIT', 'CREDIT')),
                                amount NUMERIC(19,4) NOT NULL CHECK (amount > 0),
                                currency CHAR(3) NOT NULL,
                                effective_date TIMESTAMP WITH TIME ZONE NOT NULL,
                                posted_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
                                created_by VARCHAR(100) NOT NULL,
                                idempotency_key VARCHAR(128) UNIQUE NOT NULL,
                                reference_type VARCHAR(50),
                                reference_id UUID,
                                narrative TEXT,
                                hash CHAR(64) NOT NULL,
                                previous_hash CHAR(64),
                                metadata JSONB,
                                status VARCHAR(20) NOT NULL CHECK (status IN ('PENDING', 'POSTED', 'REVERSED'))
);