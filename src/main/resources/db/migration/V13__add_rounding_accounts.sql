-- Dedicated accounts for sub-unit FX rounding reconciliation (distinct from 4004/5011 unrealised FX P&L)
INSERT INTO accounts (id, account_code, account_name, account_type, sub_type, currency)
VALUES
    (gen_random_uuid(), '4005', 'FX Rounding Gain', 'Revenue', 'Other Income', 'INR'),
    (gen_random_uuid(), '5012', 'FX Rounding Loss', 'Expense', 'Other Expense', 'INR')
    ON CONFLICT (account_code) DO NOTHING;

-- ROLLBACK STRATEGY:
-- DELETE FROM accounts WHERE account_code IN ('4005', '5012');