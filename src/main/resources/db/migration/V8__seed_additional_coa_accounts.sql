-- Seed additional Chart of Accounts needed for FX Holding, Rewards & Promotional Credits
INSERT INTO accounts (id, account_code, account_name, account_type, sub_type, currency)
VALUES
    (gen_random_uuid(), '1004', 'FX Conversion Holding (INR)', 'Asset', 'Current Asset', 'INR'),
    (gen_random_uuid(), '1005', 'FX Conversion Holding (USD)', 'Asset', 'Current Asset', 'USD'),
    (gen_random_uuid(), '2030', 'Rewards Points Liability', 'Liability', 'Current Liability', 'INR'),
    (gen_random_uuid(), '5004', 'Marketing Promotional Expense', 'Expense', 'Marketing Expense', 'INR')
    ON CONFLICT (account_code) DO NOTHING;

-- ROLLBACK STRATEGY:
-- DELETE FROM accounts WHERE account_code IN ('1004', '1005', '2030', '5004');