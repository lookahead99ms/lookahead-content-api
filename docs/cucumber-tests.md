# Cucumber acceptance tests

Feature scenarios are implemented by Java steps. The normal suite uses synthetic
fixtures, mocked account storage, the real security filter chain and production
validation/error handlers. It does not use PostgreSQL, Docker or Testcontainers.
Authentication scenarios create an embedded HTTP server on a temporary loopback
port; it is closed after the suite. Other component scenarios run in process.

## Layout

- `src/test/atdd/component/*.feature`: default component scenarios.
- `src/test/atdd/live-dependency/*.feature`: optional existing-service scenarios.
- `src/test/java/com/lookahead/learning/content/atdd`: Java glue and JUnit Platform suite.
- `src/test/java/com/lookahead/learning/content/*`: existing JUnit tests remain.
- `src/test/resources/accounts`: shared synthetic data, not real users or secrets.

Maven copies the feature tree to the test classpath under `atdd`. The directory
names use lowercase and hyphens to avoid spaces in tooling commands.

## Run without containers

Use Java 21:

```sh
./mvnw -Dtest=CucumberAtddTest test
```

`./mvnw test` also selects component features by default alongside existing JUnit
checks. Surefire reports are in `target/surefire-reports`; the Cucumber JSON
report is `target/cucumber/report.json`. Both are generated, ignored files.

To validate live feature discovery and matching Java steps without making any
HTTP requests, authenticating, or starting services:

```sh
./mvnw -Patdd-live-discovery -Dtest=CucumberAtddTest test
```

A dry run is syntax/glue validation only, not a passing persistence test.

## Optional existing-service checks

Do not start containers for these tests. Live execution is disabled by default
and requires an already available, explicitly chosen isolated API on port 4322
with the synthetic catalog mounted. Port 4320 is deliberately rejected to
protect development plans. There are no Docker startup/stop hooks.

Only when a live run is explicitly wanted, supply `ATDD_LIVE_ENABLED=true`,
`ATDD_BASE_URL=http://127.0.0.1:4322` and `ATDD_PASSWORD_FILE` pointing to the
ignored seed-password file, then run:

```sh
./mvnw -Patdd-live -Dtest=CucumberAtddTest test
```

The hook rejects missing opt-in or configuration. Every scenario creates its own
plan and attempts to delete only that plan as its original owner, even after a
failed assertion. Cleanup errors fail the scenario. Passwords are read from the
file and are not embedded in features, commands or reports.

## Coverage and migration boundary

Component features cover anonymous access, CSRF, invalid credentials, session
rotation, browser isolation, logout, trusted catalog/access/route validation,
legacy unknown provenance, and safe storage-error mapping. Storage failures are
simulated; these scenarios do not prove PostgreSQL transaction behavior.

Live features cover exact saved snapshots across login, cross-account read/write
isolation, attempt versus completion, independent session completion, identical
retry, conflicting retry keys and stale revisions.

The Python verifier is retained during migration. Its ten-persona matrix,
concurrent write races, version/recovery/extension history, full legacy import
roundtrip, payload-size boundaries, account-comparison guard, restricted metadata
matrix and restart/backup/outage continuation are not all ported to Cucumber yet.
The existing JUnit tests also retain deeper transaction/provenance coverage.
Do not delete the Python runner or claim full replacement parity from this batch.


## Autonomous batch additions

`plan-integrity.feature` adds tampered route/canonical/week/pin cases, the
expected-account guard, concurrent identical retry and competing-edit races,
recovery history, implicit extension rejection, complete legacy import/replay,
and a missing revision case. `LiveFeatureDiscoveryTest` validates every live
feature against its Java steps in dry-run mode during the normal suite, without
HTTP or lifecycle hooks. The authentication component now distinguishes a
storage outage from bad credentials.

These additions do not complete migration: the ten-persona/full restricted
metadata matrix, explicit extension success, HTTP size boundaries and coordinated
restart/backup/outage continuation still retain Python ownership. Infrastructure
callers continue to use that verifier until actual Java parity and live evidence
exist. Do not replace them with an unverified wrapper or remove their cleanup.
