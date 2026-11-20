CREATE TABLE IF NOT EXISTS partial_refunds (
                                               refund_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    original_journal_id UUID NOT NULL,
    refund_journal_id UUID NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    original_amount NUMERIC(19,4) NOT NULL,
    refund_amount NUMERIC(19,4) NOT NULL CHECK (refund_amount > 0),
    fee_refunded NUMERIC(19,4) NOT NULL DEFAULT 0,
    fee_policy VARCHAR(20) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_refund_orig_idem UNIQUE (original_journal_id, idempotency_key)
    );

-- ROLLBACK STRATEGY:
-- DROP TABLE IF EXISTS partial_refunds;