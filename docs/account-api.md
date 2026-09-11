# Local account API

The `accounts` profile adds PostgreSQL-backed, account-owned plans to the existing
Spring API. Local synthetic login also requires `local-test` and
`APP_DEPLOYMENT_ENVIRONMENT=local`. Creating missing synthetic accounts additionally
requires `APP_LOCAL_TEST_SEED_ENABLED=true` and a mounted
`app.local-test.seed-password` property. Existing accounts can log in with seeding disabled. The ten usernames are `learner01` through
`learner10`; credentials are generated locally by infrastructure, never recorded
in this repository. User 09 has only the available DSA topic grants. Seed replay
inserts missing identities only and preserves existing credentials, grants and data.

This document describes the implemented HTTP contract. For a checkout-independent
walkthrough using public synthetic fixtures, see [standalone accounts](standalone-accounts.md).

## Local execution

Infrastructure owns orchestration scripts, database lifecycle and secret mounts.
Backend independently builds and operates the API on 4320 and its isolated tests
on 4322, using API-only commands that preserve PostgreSQL. The API owns `tools/accounts/bootstrap.sql`, `tools/accounts/bootstrap.sh`, the migration
entrypoint, and `src/main/resources/db/migration/`.

Runtime configuration:

| Setting | Value |
| --- | --- |
| `SPRING_PROFILES_ACTIVE` | `accounts,local-test` for the approved local exercise |
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://account-db:5432/lookahead_accounts` |
| `SPRING_DATASOURCE_USERNAME` | `lookahead_app` |
| `SPRING_CONFIG_IMPORT` | `configtree:/run/secrets/` (required by account profile) |
| Mounted properties | `spring.datasource.password`, `app.local-test.seed-password` |
| `APP_ACCOUNTS_CATALOG_PATH` | Read-only mounted trusted `account-catalog/v1` metadata file |

Bootstrap runs with the database administrator solely to create missing runtime
and migration roles. Runtime has DML access and cannot migrate schema. Bootstrap
replay does not reset passwords. Apply migrations with the packaged API image:

```sh
java -Dloader.main=com.lookahead.learning.content.migration.AccountMigration \
  -cp /app/app.jar org.springframework.boot.loader.launch.PropertiesLauncher
```

Supply `SPRING_FLYWAY_URL`, `SPRING_FLYWAY_USER=lookahead_migrator`, and mounted
`/run/secrets/spring.flyway.password`. This command starts no HTTP server or seed
runner. Never edit an applied migration. V1's schema-USAGE grant can produce a
harmless PostgreSQL warning because the migrator does not own `public`; bootstrap
now grants runtime USAGE explicitly. Application table privileges are granted by
the owning migrator within V1.

Account readiness checks app-role access to the expected schema and migration;
liveness is independent of PostgreSQL. An API restart expires in-memory sessions,
so log in again to resume the persisted account plan. Ordinary shutdown preserves
the database volume; infrastructure owns backup/restore and isolated lifecycle tests.

## HTTP contract

Routes below are relative to `/api/v1`. Success responses use `{data,timestamp}`;
logout and delete return `204`. Private responses forbid caching. Authentication
uses the HttpOnly `LOOKAHEAD_SESSION` cookie, SameSite=Lax, Secure except in the
explicit local HTTP test profile. Use the same-origin frontend proxy.

| Request | Contract |
| --- | --- |
| `GET /auth/csrf` | Anonymous permitted; `data:{token,headerName,parameterName}`. |
| `POST /auth/login` | URL-encoded username/password plus returned CSRF header; account view on success. Fetch fresh CSRF afterward. |
| `GET /auth/me` | `data:{accountId,username,displayName,topicGrants}` from server-owned identity/grants. |
| `POST /auth/logout` | CSRF required; invalidates session and cookie. Anonymous browser storage is untouched. |
| `GET /account-catalog` | Authenticated catalog digest, supported algorithms/rankings and topic IDs. |
| `POST /plans` | `{goal,snapshot,provenance}` creates an immutable schedule with revision 1. |
| `POST /plans/imports` | `{sourceSchemaVersion:"study-plan-local/v1",localSnapshot,provenance}` explicitly imports the unchanged local value. |
| `GET /plans?limit=100` | Owner-only `data:{plans,nextCursor}`. Default limit 20, maximum 100; creation-time descending with ID tie-break. |
| `GET /plans/{id}` | Current plan view. |
| `GET /plans/{id}/versions/{versionId}` | Historical immutable snapshot with current per-plan progress. |
| `POST /plans/{id}/activity` | `{expectedRevision,versionId,operations:[...]}` writes 1–100 operations atomically. |
| `POST /plans/{id}/versions` | `{expectedRevision,goal,snapshot,provenance,reason,recovery}` saves a new schedule version. |
| `DELETE /plans/{id}` | `If-Match: "revision-N"` deletes only the owner's plan and private data. |

Every write needs a UUID `Idempotency-Key`. The same account/key/request replays
the original committed result; changed content with that key conflicts. Key locks,
revision checks, activity, projections and receipts share one PostgreSQL
transaction. Plan IDs and ownership come from the server. Unknown and other-owner
IDs both return `404`. Deleted-plan receipt payloads are erased; old creation keys
cannot resurrect a deleted plan.

The frontend sends `X-LookAhead-Account` with its expected account UUID on every
plan request. A mismatch with the authenticated session returns `401 ACCOUNT_CHANGED`
before controller access, preventing a stale browser tab from saving into another
account after an account switch. This header only compares identity; it cannot
authenticate or grant access. It is optional for direct API clients.

Plan views contain `planId`, `versionId`, `revision`, `goal`, `snapshot`,
`provenance`, `progress`, `recovery`, `restrictedContentIds`, `createdAt`, `updatedAt`.
Progress contains `completedContentIds`, `completedSessionIds`,
`attemptedContentIds`, `needsReviewContentIds`, `notes`, `sessionOutcomes`, and
optional raw `legacySource`. Imported summaries retain their historical uncertainty;
out-of-plan legacy evidence stays in `legacySource` without becoming active progress.

Activity operations:

- `{type:"recordAttempt",assignmentId,canonicalContentId,outcome:"attempted"|"needs-review"}`
- `{type:"setSessionCompletion",assignmentId,completed:boolean}`
- `{type:"setContentCompletion",canonicalContentId,completed:boolean}`
- `{type:"setNote",canonicalContentId,text}`; empty text clears the note.

Attempts never grant completion or mastery. Notes and progress belong to one plan
and survive its schedule versions. Each version pins validated session-to-canonical
membership so a catalog/ranking release cannot rewrite its progress identities.
Current trusted grants still govern new activity. Owned notes remain editable when
content access is revoked; a saved plan does not grant access to answer bodies.

## Pins and recovery

Provenance uses `snapshotSchemaVersion:"study-plan/v1"`, `origin`,
`algorithmVersion`, `catalogVersion`, and nullable `rankingVersion` for non-DSA
plans. New saves require installed explicit pins. Legacy imports retain unknown
historical pins as null and record current validation separately. Snapshot JSON is
not regenerated by the backend and is not a certificate of scheduler execution.

Recovery is an explicit version save, not an automatic read side effect. Use
`reason:"recovery"` with `strategy:"fixed-window"`; `deadlineDays` and daily hours
must remain unchanged. `reason:"extend-deadline"` and `strategy:"explicit-extension"`
require a later deadline, with snapshot horizon extended explicitly. All horizons
are 1–180 days. `elapsedDays` cannot move backward, including on `update-plan`.
`recovery` includes `elapsedDays`, `deadlineDays`, `deferredContentIds`, and optional
`deferredSessions:[{assignment,originalDay,reason}]` preserving original session
details. The frontend owns preview/confirmation and scheduling within the budget.

Recovery preserves assignment identities and fields; only review origin/due-day
fields can change with validated parent spacing. Prior scheduled, future-review,
and deferred sessions form the permitted source pool. Omitted work must be disclosed.
Reviewing a stored version does not mutate it. A revision conflict returns `409`
and the current owner revision; preserve the browser draft and resolve explicitly.

## Verification

Run `./mvnw --batch-mode --no-transfer-progress verify` for Java tests/build. The
Dockerfile repeats tests in Java 21. The standard-library HTTP suite requires a
running isolated account API and its matching catalog:

```sh
python3 tools/accounts/cucumber_probe.py --base-url http://127.0.0.1:4322 \
  --password-file /path/to/ignored/seed-password --catalog src/test/resources/accounts/catalog.json
```

Use `--help` for `prepare`, `resume`, `outage`, and `cleanup` lifecycle probes.
Infrastructure performs service restarts; this script never starts/stops containers.
Its full test removes only plans created by that run. Generated continuation state
and cookies belong in ignored scratch storage. HTTP fixtures test account behavior;
Frontend scheduler and browser recovery behavior require separate integration checks.

Errors distinguish malformed input (`400`), unauthorized (`401`), CSRF/access
denial (`403`), missing resources (`404`), stale revisions/reused keys (`409`),
deleted plan replay (`410`), plan bodies over 8 MiB or activity bodies over 256 KiB (`413`), semantic validation (`422`),
missing revision (`428`), and unavailable storage (`503`). JSON depth is limited to
32; notes to 1,000 characters. A network timeout may follow a committed save: retry
the same key. Never report a durable save until the API confirms it.

## Code organization and environments

See [architecture](architecture.md) for controller/service/repository boundaries and
[environment setup](environments.md) for local development and production planning.
The previous `com.lookahead.learning.content.accounts.AccountMigration` entrypoint
remains a compatibility launcher for existing Infra commands.

Email/password registration and required profile fields are described in [account-registration.md](account-registration.md). Availability is advertised by `/api/v1/auth/options`; existing local synthetic login remains guarded independently.
