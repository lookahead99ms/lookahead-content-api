\set ON_ERROR_STOP on
\getenv appPassword LOOKAHEAD_APP_PASSWORD
\getenv migratorPassword LOOKAHEAD_MIGRATOR_PASSWORD
SELECT format('CREATE ROLE lookahead_app LOGIN PASSWORD %L NOSUPERUSER NOCREATEDB NOCREATEROLE', :'appPassword')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'lookahead_app') \gexec
SELECT format('CREATE ROLE lookahead_migrator LOGIN PASSWORD %L NOSUPERUSER NOCREATEDB NOCREATEROLE', :'migratorPassword')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'lookahead_migrator') \gexec
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
GRANT USAGE, CREATE ON SCHEMA public TO lookahead_migrator;
GRANT USAGE ON SCHEMA public TO lookahead_app;
GRANT CONNECT ON DATABASE lookahead_accounts TO lookahead_app, lookahead_migrator;
