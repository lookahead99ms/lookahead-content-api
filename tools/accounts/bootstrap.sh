#!/bin/sh
set -eu
# Execute with psql available and these three secret files mounted; never use set -x.
export PGPASSWORD="$(cat /run/secrets/account-db-bootstrap-password)"
export LOOKAHEAD_APP_PASSWORD="$(cat /run/secrets/spring.datasource.password)"
export LOOKAHEAD_MIGRATOR_PASSWORD="$(cat /run/secrets/spring.flyway.password)"
test -n "$PGPASSWORD" && test -n "$LOOKAHEAD_APP_PASSWORD" && test -n "$LOOKAHEAD_MIGRATOR_PASSWORD"
exec psql -h account-db -U lookahead_bootstrap -d lookahead_accounts -v ON_ERROR_STOP=1 -f /contract/bootstrap.sql
