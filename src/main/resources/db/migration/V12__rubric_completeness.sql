-- 1. Additional CoA accounts for strict per-currency equity & unrealised FX P&L
INSERT INTO accounts (id, account_code, account_name, account_type, sub_type, currency)
VALUES
    (gen_random_uuid(), '3002', 'Retained Earnings (USD)', 'Equity', 'Retained Earnings', 'USD'),
    (gen_random_uuid(), '4004', 'Unrealised FX Gain', 'Revenue', 'Other Income', 'INR'),
    (gen_random_uuid(), '5011', 'Unrealised FX Loss', 'Expense', 'Other Expense', 'INR')
    ON CONFLICT (account_code) DO NOTHING;

-- 2. Materialised Balance Snapshots cache (Day 4 deliverable)
CREATE TABLE IF NOT EXISTS balance_snapshots (
                                                 account_id UUID PRIMARY KEY REFERENCES accounts(id),
    currency CHAR(3) NOT NULL,
    balance NUMERIC(19,4) NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
    );

-- 3. Persistent FX Revaluation State (prevents double-booking full delta nightly)
CREATE TABLE IF NOT EXISTS fx_revaluation_state (
                                                    currency_pair VARCHAR(7) PRIMARY KEY,
    last_revalued_rate NUMERIC(18,8) NOT NULL,
    last_revalued_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
    );

-- ROLLBACK STRATEGY:
-- DROP TABLE IF EXISTS fx_revaluation_state;
-- DROP TABLE IF EXISTS balance_snapshots;
-- DELETE FROM accounts WHERE account_code IN ('3002', '4004', '5011');
INSERT INTO accounts (id, account_code, account_name, account_type, sub_type, currency)
VALUES (gen_random_uuid(), '3002', 'Retained Earnings (USD)', 'Equity', 'Retained Earnings', 'USD')
    ON CONFLICT (account_code) DO NOTHING;