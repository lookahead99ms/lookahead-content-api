# Local development and production environments

These profiles separate local synthetic account testing from shared development
and production configuration. Start with the [standalone account walkthrough](standalone-accounts.md)
when using this repository independently of the platform.

## Supported profiles

| Profile | Purpose | Accounts | Local password login | Cookie |
| --- | --- | --- | --- | --- |
| `local` (host default) | Operational API development without PostgreSQL | Off | Off | No account session |
| `local-accounts` | Local account development; expands to `local,accounts,local-test` | PostgreSQL required | Enabled | HttpOnly, SameSite=Lax, HTTP permitted |
| `dev` | Shared development environment; expands to `accounts` | PostgreSQL required | Disabled | HttpOnly, SameSite=Lax, Secure |
| `accounts,local-test` | Existing integrated Compose contract | PostgreSQL required | Enabled only with `APP_DEPLOYMENT_ENVIRONMENT=local` | Same as `dev` |
| `prod` (image default) | Operational foundation with graceful shutdown | Off | Off | No account session |
| `production` | Production account configuration baseline; expands to `prod,accounts` | PostgreSQL required | Disabled | HttpOnly, SameSite=Lax, Secure |

Shared DEV and production account profiles are **not launch-ready authentication**. The current
synthetic identity remains limited to local testing. Email/password registration is separately opt-in with `LOOKAHEAD_REGISTRATION_ENABLED=true`; it is enabled in the local Compose stack. See [registration](account-registration.md). Google provider integration and shared session topology remain separate work.
Mixing `local-test` with `prod` or `production` fails startup. Synthetic account
seeding is disabled by default, requires explicit local authorization/configuration,
and never resets existing identities or progress.

Profile groups live in the base `application.yml`; environment-specific files
contain overrides. See Spring's [profile documentation](https://docs.spring.io/spring-boot/reference/features/profiles.html).

## Local development workflow

Use the sibling `lookahead-learning-infra` Compose runbooks for the integrated
frontend, API and PostgreSQL stack. Backend operates API builds and services on 4320/4322 using Infra-owned scripts.
Infra operates PostgreSQL, migration orchestration, backup and recovery, including
the isolated restore stack on 4323. The platform UI uses port 4316. Preserve existing ports and volumes;
ordinary shutdown must retain account data. The API listens on container port
8080; the current integrated host API binding is loopback port 4320. Use isolated API port 4322 for mutating contract tests; preserve the integrated
dataset and keep database outages coordinated.

The `local-accounts` profile is an alternative profile selection for local account development;
existing Compose settings do not need to change. Required configuration is:

```dotenv
SPRING_PROFILES_ACTIVE=local-accounts
SPRING_DATASOURCE_URL=jdbc:postgresql://account-db:5432/lookahead_accounts
SPRING_DATASOURCE_USERNAME=lookahead_app
LOOKAHEAD_SECRETS_DIRECTORY=/run/secrets/
APP_ACCOUNTS_CATALOG_PATH=/run/lookahead/catalog.json
APP_CORS_ALLOWEDORIGINS=http://localhost:4316,http://127.0.0.1:4316
```

Use the exact catalog property described in the [account runbook](account-api.md)
and the Infra mount contract. The secret directory must exist and end with `/`.
It contains a `spring.datasource.password` file and, only when explicitly seeding,
an `app.local-test.seed-password` file. Keep actual secrets outside Git and images.
`APP_LOCAL_TEST_SEED_ENABLED=true` enables the guarded ten-account bootstrap;
leave it false during ordinary development. Config-tree loading is required by
account profiles, so missing mounts fail startup rather than selecting credentials.

For the database-free foundation, run `./mvnw spring-boot:run` with a free
`SERVER_PORT`. For IDE debugging with accounts, first coordinate a loopback-only
PostgreSQL endpoint and point the JDBC URL at it; the internal Compose hostname
is not a host-machine database endpoint. Do not expose PostgreSQL publicly or
change the shared stack simply to attach a debugger.

CORS permits exact origins and credentials. `localhost` and `127.0.0.1` are
different origins; configure the list with `APP_CORS_ALLOWEDORIGINS` when both
are needed. `FRONTEND_ORIGIN` remains the single-origin convenience setting.
A same-origin development proxy is also supported. Cross-origin browser calls
must send credentials and the CSRF header; CORS itself is not authentication.

## Shared DEV environment

DEV means shared nonproduction infrastructure, not a developer's laptop. Select
`SPRING_PROFILES_ACTIVE=dev`; the API sets `app.deployment-environment=development`.
Use a separate database, secrets, catalog release, origins and infrastructure state
from both Local and PROD. Synthetic identity is disabled; approved shared identity
integration is required before account access is usable. Do not add `local-test`
to DEV to bypass that requirement. DEV uses Secure cookies and therefore requires
HTTPS for browser sessions. Infra owns environment manifests and service scripts;
the API owns executable code, schema migrations and database role/grant SQL.

## Configuration and database responsibilities

Common non-secret settings belong in YAML; deployment overrides belong in the
orchestrator environment. Passwords belong in mounted secret files. A custom
`SPRING_CONFIG_IMPORT` can replace the config-tree import when the deployment
uses another reviewed provider. External property precedence follows Spring's
[external configuration contract](https://docs.spring.io/spring-boot/reference/features/external-config.html).

The runtime requires the dedicated `lookahead_app` role. It has DML access and
cannot apply schema migrations. A separate one-shot migration job uses
`lookahead_migrator`; bootstrap ownership is separate from both. The preferred
migration main class is `com.lookahead.learning.content.migration.AccountMigration`.
The old `com.lookahead.learning.content.accounts.AccountMigration` forwards to it,
preserving existing Infra commands. Never edit an already-applied SQL migration.

Pool defaults are six connections, a three-second connection timeout and a
two-second validation timeout. Override only these exposed pool controls using
`SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE`,
`SPRING_DATASOURCE_HIKARI_CONNECTION_TIMEOUT` and
`SPRING_DATASOURCE_HIKARI_VALIDATION_TIMEOUT`. Size the pool against the database
connection budget and replica count; local defaults are not production sizing.

## Production rollout plan

1. Finalize production identity and account/grant ownership. Implement
   authentication, logout/revocation and authorization checks
   against that contract. Decide durable/shared sessions or another reviewed
   identity model before adding replicas; current sessions live in process memory.
2. Infra defines AWS networking, compute, managed PostgreSQL, secrets, permissions,
   storage/CDN and monitoring for the whole platform. Choose the service mapping
   in review; these API changes do not provision resources or select a vendor flow.
3. Configure HTTPS termination, trusted proxy handling, allowed browser origins,
   secure cookies and database TLS. Keep management access constrained and health
   details private. Review whether production API documentation should be exposed.
4. Deploy a tested, pinned image plus a versioned read-only metadata catalog.
   Supply production secrets externally. Run migrations with the migration role
   before runtime readiness is admitted; never package curriculum or seed secrets.
5. Verify identity, account isolation, CSRF, grant changes, concurrent revisions,
   idempotent retries, migrations and backup/restore against the target services.
   Exercise graceful draining and database outage recovery. Readiness checks the
   authenticated database/schema; liveness remains independent of database outages.
6. Establish monitoring and release gates, database backup retention and a tested
   restore procedure. Roll out with backward-compatible schema changes and a
   documented application rollback path; application rollback does not undo SQL.

Local Docker and PostgreSQL checks demonstrate local behavior. AWS IAM, VPC, TLS,
managed failover and production capacity need separate target-environment evidence.

## Verification

Run `./mvnw clean verify` after source/package changes. Profile tests cover group
expansion, seed defaults, cookie settings and exposed pool overrides; existing
security and snapshot tests cover the API contracts. The separate
`tools/accounts/cucumber_probe.py` suite exercises the real PostgreSQL HTTP boundary;
use the account runbook and an isolated test instance for its mutating scenarios.
Record the tested source/image separately from any previously running image.
