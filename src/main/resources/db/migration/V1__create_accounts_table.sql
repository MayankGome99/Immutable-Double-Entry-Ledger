CREATE TABLE accounts (
                          id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                          account_code VARCHAR(10) UNIQUE NOT NULL,
                          account_name VARCHAR(100) NOT NULL,
                          account_type VARCHAR(20) NOT NULL,
                          sub_type VARCHAR(50) NOT NULL,
                          currency CHAR(3) NOT NULL,
                          created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);