CREATE TABLE accounts (
    id uuid PRIMARY KEY,
    username varchar(100) NOT NULL UNIQUE,
    display_name varchar(160) NOT NULL,
    password_hash varchar(255) NOT NULL,
    enabled boolean NOT NULL DEFAULT true
);
CREATE TABLE account_grants (
    account_id uuid NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    topic_id varchar(256) NOT NULL,
    PRIMARY KEY (account_id, topic_id)
);
CREATE TABLE plans (
    id uuid PRIMARY KEY,
    account_id uuid NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    revision bigint NOT NULL CHECK (revision > 0),
    current_version uuid NOT NULL,
    goal varchar(160) NOT NULL,
    progress jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (account_id, id)
);
CREATE INDEX plans_owner_updated ON plans(account_id, updated_at DESC, id);
CREATE TABLE plan_versions (
    id uuid PRIMARY KEY,
    account_id uuid NOT NULL,
    plan_id uuid NOT NULL,
    parent_version uuid,
    snapshot jsonb NOT NULL,
    snapshot_digest varchar(80) NOT NULL,
    membership jsonb NOT NULL,
    provenance jsonb NOT NULL,
    recovery jsonb NOT NULL,
    reason varchar(160) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (account_id, plan_id) REFERENCES plans(account_id, id) ON DELETE CASCADE,
    UNIQUE (account_id, plan_id, id)
);
ALTER TABLE plans ADD CONSTRAINT current_version_owned
    FOREIGN KEY (account_id, id, current_version)
    REFERENCES plan_versions(account_id, plan_id, id) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE plan_versions ADD CONSTRAINT parent_version_owned
    FOREIGN KEY (account_id, plan_id, parent_version)
    REFERENCES plan_versions(account_id, plan_id, id) DEFERRABLE INITIALLY DEFERRED;
CREATE TABLE plan_activity (
    id uuid PRIMARY KEY,
    account_id uuid NOT NULL,
    plan_id uuid NOT NULL,
    version_id uuid NOT NULL,
    revision bigint NOT NULL,
    kind varchar(40) NOT NULL,
    payload jsonb NOT NULL,
    recorded_at timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (account_id, plan_id, version_id)
        REFERENCES plan_versions(account_id, plan_id, id) ON DELETE CASCADE
);
CREATE TABLE mutation_receipts (
    account_id uuid NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    mutation_key uuid NOT NULL,
    request_hash varchar(80) NOT NULL,
    plan_id uuid NOT NULL,
    response jsonb,
    response_status integer NOT NULL,
    deleted boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (account_id, mutation_key)
);
GRANT USAGE ON SCHEMA public TO lookahead_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON accounts, account_grants, plans,
    plan_versions, plan_activity, mutation_receipts TO lookahead_app;
GRANT SELECT ON flyway_schema_history TO lookahead_app;
