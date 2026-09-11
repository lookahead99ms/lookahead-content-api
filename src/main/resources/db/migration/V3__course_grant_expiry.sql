ALTER TABLE account_grants ADD COLUMN valid_until timestamptz;
COMMENT ON COLUMN account_grants.valid_until IS 'Optional server-owned access expiry; NULL is an explicitly indefinite grant. No billing is implied.';
