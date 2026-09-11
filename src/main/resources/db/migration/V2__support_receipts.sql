-- Minimal durable dispatch receipts: no message, email address or image bytes are stored here.
-- Keep keys until account deletion so an old uncertain retry cannot become a new send.
CREATE TABLE support_receipts (
    account_id uuid NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    request_key varchar(80) NOT NULL,
    request_hash char(64) NOT NULL,
    reference uuid NOT NULL UNIQUE,
    status varchar(20) NOT NULL CHECK (status IN ('unconfirmed','accepted')),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (account_id, request_key)
);
CREATE INDEX support_receipts_account_created ON support_receipts(account_id,created_at);
GRANT SELECT, INSERT, UPDATE, DELETE ON support_receipts TO lookahead_app;
