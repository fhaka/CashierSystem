-- New accounts are plain cashiers unless an admin chooses otherwise.
ALTER TABLE cashiers ALTER COLUMN role SET DEFAULT 'CASHIER';

-- Lock an account for a few minutes after repeated wrong passwords.
ALTER TABLE cashiers ADD COLUMN failed_logins INTEGER DEFAULT 0 NOT NULL;
ALTER TABLE cashiers ADD COLUMN locked_until DATETIME(6);

-- Sign-in sessions survive a server restart. Only a SHA-256 hash of the token is stored,
-- so a database backup cannot be used to sign in.
CREATE TABLE auth_sessions (
    token_hash VARCHAR(64) NOT NULL,
    cashier_id BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    last_seen_at DATETIME(6) NOT NULL,
    PRIMARY KEY (token_hash),
    CONSTRAINT fk_auth_sessions_cashier FOREIGN KEY (cashier_id) REFERENCES cashiers (id)
);

CREATE INDEX idx_auth_sessions_cashier ON auth_sessions (cashier_id);
