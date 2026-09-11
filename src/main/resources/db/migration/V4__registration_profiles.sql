ALTER TABLE accounts ALTER COLUMN username TYPE varchar(254);
CREATE TABLE account_profiles (
    account_id uuid PRIMARY KEY REFERENCES accounts(id) ON DELETE CASCADE,
    first_name varchar(80) NOT NULL,
    last_name varchar(80) NOT NULL,
    email varchar(254) NOT NULL UNIQUE,
    country_code char(2) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT email_normalized CHECK (email = lower(email)),
    CONSTRAINT country_code_format CHECK (country_code ~ '^[A-Z]{2}$')
);
GRANT SELECT, INSERT, UPDATE, DELETE ON account_profiles TO lookahead_app;
