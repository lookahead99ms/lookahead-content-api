# Standalone local account demo

This walkthrough exercises the actual account API with a two-record synthetic
catalog. It needs no frontend, private curriculum or sibling repository. It does
need a local PostgreSQL service; the API does not embed or provision a database.
Use an empty demo database, separate from any existing application's data.

## Prerequisites

- A macOS, Linux or WSL shell, Java 21+ and Python 3.10+; use the included Maven Wrapper.
- PostgreSQL 17+ running on loopback, plus its `createdb` and `psql` clients.
- An administrator credential for creating the demo database and application roles.

The examples use PostgreSQL port 5432 and API port 8081. Choose free ports and pass
matching `--database-port` / `--port` options below. Use your normal PostgreSQL
installation or infrastructure provider to start the database. Platform service
orchestration and backup/restore scripts belong to the infrastructure repository;
this repository owns its schema, roles, API configuration and test client.

## Generate API configuration and local secrets

From the repository root:

```sh
./mvnw --batch-mode --no-transfer-progress clean verify
python3 tools/accounts/prepare_demo.py --port 8081 --database-port 5432
```

This creates `.local/accounts/application.properties` and three randomly generated
secret files under `.local/accounts/secrets/`. Directories are private to your user
and files are mode 600. The helper prints paths, never passwords. It preserves
existing secrets and rejects conflicting configuration or symbolic links. `.local/`
is excluded from Git and Docker context.

The generated API configuration selects `local-accounts`, points to the included
`src/test/resources/accounts/catalog.json`, and enables the guarded ten-account
seed. It binds HTTP to loopback. This is synthetic local login, not a production
identity provider.

## Prepare the external database

Create the dedicated empty database with your PostgreSQL administrator. The user
name below is an example administrative role, not a stored credential:

```sh
createdb --host=127.0.0.1 --port=5432 --username=postgres lookahead_accounts
```

Set up the restricted application roles using the generated passwords. `psql`
may prompt for your administrator password; do not put that password in these
commands or in a committed file. The substitutions below read generated secrets
without putting their values into shell history.

```sh
export LOOKAHEAD_APP_PASSWORD="$(cat .local/accounts/secrets/spring.datasource.password)"
export LOOKAHEAD_MIGRATOR_PASSWORD="$(cat .local/accounts/secrets/spring.flyway.password)"
psql --host=127.0.0.1 --port=5432 --username=postgres \
  --dbname=lookahead_accounts --file=tools/accounts/bootstrap.sql
unset LOOKAHEAD_APP_PASSWORD LOOKAHEAD_MIGRATOR_PASSWORD
```

Bootstrap creates missing roles and does not reset existing passwords. If these
roles already exist in your PostgreSQL instance, use their matching credentials
or a separate demo instance; generating new local files cannot change them.
The runtime role is `lookahead_app`; the migration role is `lookahead_migrator`.

Apply the API's versioned schema using the separate migration entrypoint:

```sh
SPRING_FLYWAY_URL=jdbc:postgresql://127.0.0.1:5432/lookahead_accounts \
SPRING_FLYWAY_USER=lookahead_migrator \
LOOKAHEAD_MIGRATION_PASSWORD_FILE="$PWD/.local/accounts/secrets/spring.flyway.password" \
java -Dloader.main=com.lookahead.learning.content.migration.AccountMigration \
  -cp target/lookahead-content-api.jar org.springframework.boot.loader.launch.PropertiesLauncher
```

The job starts no HTTP server. Running it again applies only unapplied migrations;
never edit an already-applied SQL migration. The runtime role cannot perform DDL.

## Run the full account API

```sh
java -jar target/lookahead-content-api.jar \
  --spring.config.additional-location="file:$PWD/.local/accounts/application.properties"
```

Wait for readiness:

```sh
curl --fail http://127.0.0.1:8081/actuator/health/readiness
```

The local seed creates `learner01` through `learner10` only if absent. They share
the generated password in `app.local-test.seed-password`; learner09 has only DSA
access. Ordinary restarts preserve stored plans, progress, grants and credentials.
After the first startup, `app.local-test.seed-enabled` may be set to `false` in
the ignored properties file; existing users can still log in.

## Exercise the account contract

In another terminal, run the standard-library HTTP client against this local API:

```sh
python3 tools/accounts/cucumber_probe.py --standalone \
  --base-url http://127.0.0.1:8081 \
  --password-file .local/accounts/secrets/app.local-test.seed-password \
  --catalog src/test/resources/accounts/catalog.json \
  --account-guard
```

The output is a sanitized JSON check report. The client handles CSRF and session
cookies, authenticates all ten synthetic users, creates plans, records activity and
notes, checks ownership and conflicting revisions, exercises imports/recovery and
retries, then removes only the plans it created. The catalog is synthetic metadata,
not a proprietary curriculum or production scheduler output.

To explore manually, inspect the [HTTP contract](account-api.md) and OpenAPI UI.
Account writes need a session, a current CSRF token and an `Idempotency-Key`; a plain
unauthenticated request correctly returns an error. Obtaining `/api/v1/auth/csrf` creates
a CSRF token; login rotates the session, so obtain a fresh token after login.

## Stop and retain data

Stop the foreground API with Ctrl+C. PostgreSQL data remains under your database
service's normal storage policy. Deleting the API's generated configuration does
not delete database records, and generating new passwords does not rotate existing
roles or accounts. Manage backup, restore and deliberate database removal through
your PostgreSQL/infrastructure tooling.

Shared DEV and PROD are separate environments, with synthetic identity disabled.
See [environment configuration](environments.md) for their prerequisites.
