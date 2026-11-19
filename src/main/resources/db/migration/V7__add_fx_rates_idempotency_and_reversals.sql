-- Day 6, 8 & 9: Multi-Currency FX Snapshots, Idempotency Keys & Reversals

-- 1. Immutable Exchange Rate Snapshots Table
CREATE TABLE IF NOT EXISTS exchange_rate_snapshots (
                                                       snapshot_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    base_currency CHAR(3) NOT NULL,
    quote_currency CHAR(3) NOT NULL,
    rate NUMERIC(18,8) NOT NULL CHECK (rate > 0),
    inverse_rate NUMERIC(18,8) NOT NULL CHECK (inverse_rate > 0),
    source VARCHAR(50) NOT NULL,
    captured_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    valid_from TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    valid_until TIMESTAMPTZ
    );

CREATE INDEX IF NOT EXISTS idx_fx_pair_validity
    ON exchange_rate_snapshots (base_currency, quote_currency, valid_until);

-- Seed sample exchange rates (USD/INR, EUR/INR, GBP/INR, JPY/INR)
INSERT INTO exchange_rate_snapshots (base_currency, quote_currency, rate, inverse_rate, source, valid_from, valid_until)
VALUES
    ('USD', 'INR', 83.42150000, 0.01198732, 'RBI_REFERENCE', NOW(), NOW() + INTERVAL '365 days'),
    ('EUR', 'INR', 90.15000000, 0.01109262, 'RBI_REFERENCE', NOW(), NOW() + INTERVAL '365 days'),
    ('GBP', 'INR', 105.80000000, 0.00945180, 'RBI_REFERENCE', NOW(), NOW() + INTERVAL '365 days'),
    ('JPY', 'INR', 0.55600000, 1.79856115, 'RBI_REFERENCE', NOW(), NOW() + INTERVAL '365 days');

-- 2. Dedicated Idempotency Keys Table (24-hour minimum expiry)
CREATE TABLE IF NOT EXISTS idempotency_keys (
                                                idempotency_key VARCHAR(128) NOT NULL,
    user_id VARCHAR(100) NOT NULL DEFAULT 'SYSTEM',
    endpoint VARCHAR(200) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    status VARCHAR(20) NOT NULL CHECK (status IN ('PROCESSING', 'COMPLETED', 'FAILED')),
    response_status INT,
    response_body TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (user_id, idempotency_key)
    );

-- 3. Reversals & Partial Refund Audit Table
CREATE TABLE IF NOT EXISTS reversals (
                                         reversal_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    original_journal_id UUID NOT NULL,
    reversal_journal_id UUID NOT NULL,
    reversal_idempotency_key VARCHAR(128) NOT NULL,
    refund_type VARCHAR(20) NOT NULL CHECK (refund_type IN ('FULL', 'PARTIAL')),
    fee_policy VARCHAR(20) NOT NULL CHECK (fee_policy IN ('PROPORTIONAL', 'FULL', 'NONE')),
    refund_amount NUMERIC(19,4) NOT NULL CHECK (refund_amount > 0),
    reason TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (original_journal_id, reversal_idempotency_key)
    );

-- ROLLBACK STRATEGY:
-- DROP TABLE IF EXISTS reversals;
-- DROP TABLE IF EXISTS idempotency_keys;
-- DROP TABLE IF EXISTS exchange_rate_snapshots;