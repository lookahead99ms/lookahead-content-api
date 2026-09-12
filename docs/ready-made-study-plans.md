# Ready-made study-plan publication

Ready-made schedules use the existing `/content/**` publication endpoint:

- `/content/study-plans/index.json`: compact `study-plan-picker/v1` discovery JSON.
- `/content/study-plans/templates/<templateId>.json`: `study-plan-template/v1` detail.

Only paths present in the server-owned `content-publication/v1` manifest can be
read. Each asset is checked against its published SHA-256; changed bytes,
symlinks, unlisted files and traversal cannot become published content. The root
is an explicit runtime projection, not a browsable source checkout. Both content
settings remain optional for the standalone API demo; without them these paths
return `404 CONTENT_NOT_FOUND`.

The discovery index contains metadata only and may be published with `tier: public`.
Real template details use `tier: pro`, their full canonical course `scopes`, and
`scopeMatch: all`. The reader must have an enabled account and every declared
scope. Grants are rechecked on every read. Existing manifests that omit
`scopeMatch` retain their original `any` semantics; only `any` and `all` are valid.
The same matching rule is used when computing content grants.

Every asset below `/content/study-plans/` must have `contentIds: []`, including
public discovery metadata. Template metadata must never grant access to the
lessons or problems it references. Invalid entries fail publication loading.
Reading a schedule is not proof that later adoption passes current item-level
access and account policy checks.

Eligibility uses current authoritative access to the complete selected canonical
scope. A grant does not have to last as long as the schedule: seven days of current
access may cover the initial save of a thirty-day plan. Schedule duration, course
selection, access expiration and billing period are distinct. Final adoption and
scope-changing saves must recheck current access without silently dropping an
inaccessible course. A saved reference never bypasses future protected-content
reads. Access lapse does not delete or archive a plan, reset progress or establish
an archive date. Owner-bound history, deletion, receipt reconciliation and safe
progress records remain separate from access to protected lesson bodies.

Successful reads return unchanged JSON bytes, `Cache-Control: no-store, private`,
`X-Content-Type-Options: nosniff`, and `X-Content-Publication`. The publication's
version is separate from the immutable template/catalog/ranking pins inside the
JSON. Existing errors are `401 AUTHENTICATION_REQUIRED`, `403 CONTENT_SCOPE_REQUIRED`,
`404 CONTENT_NOT_FOUND`, and `503 CONTENT_UNAVAILABLE`.

Reading the index, choosing a variant and reading its detail never creates,
selects, activates or modifies an account plan. A ready-made projection is not an
ordinary generated snapshot: it contains explicit practice/recovery, review basis,
dependency arrays and immutable template provenance. Account adoption is not yet
enabled. Do not strip its provenance or substitute an older algorithm version to
save it. A versioned lossless mapping, verified published pins and atomic account
policy validation must precede that integration. Saved learner plans must remain
independent of subsequent publication changes.

## Internal adoption preparation

`ReadyMadeAdoptionPreparation` prepares an owner-bound draft from a template ID
and the raw SHA-256 shown by the picker. It loads only the matching allowlisted
Pro/all-scope asset, verifies its bytes, then checks canonical reference IDs,
routes, content types and catalog/ranking pins against the trusted account
catalog. It obtains current complete-course grants from the account repository.
It never interprets a role, product label, claimed client scope or schedule end
date as access.

The result contains exact sorted required, covered and missing course IDs plus
missing canonical content IDs. Lists are bounded by the validated source limits
(100 courses and 10,000 references); they are never silently truncated. If any
scope is missing, the result contains no mapped snapshot. Preparation persists
nothing and cannot reserve capacity or authorize a subsequent save. Expected
hash mismatch is `409 TEMPLATE_CHANGED`; invalid server-owned template/catalog
data is `503 TEMPLATE_UNAVAILABLE`. File-integrity failure retains
`503 CONTENT_UNAVAILABLE`.

The versioned mapper retains the complete authored object in `snapshot.template`,
including recovery sessions, finite coverage, future reviews and every source
pin. Its schedule projection preserves original kinds in `templateKind`, full
`requiredSessionIds` and review basis; recovery remains separate from completion
membership. Provenance identifies `ready-made-to-study-plan/v1` and the immutable
raw template, template version, blueprint and source-content pins. This is an
internal draft format, not a new accepted client snapshot contract.

The projection's `focusedDailyHours` is the configured daily cap and `bufferHours`
is zero. Existing daily-selection/recovery consumers treat these as caps; a
horizon average would hide valid work on busier days. Exact authored focused,
recovery and unused minutes remain in each template day and aggregate coverage.

There is no adoption controller or advertised adoption capability. Existing
create/import/version validation still rejects this format. Atomic activation
requires a settled lifecycle/date normalization for existing plans and one
account-wide transaction boundary shared by every reservation-changing command.
That boundary must repeat source/access validation, apply the four-ACTIVE-plan,
exclusive-subject and overlapping-UTC-date 1,080-minute limits, and preserve
ownership, revision checks and idempotent replay. Read-only preparation alone
does not establish any of those persistence guarantees.

`src/test/resources/study-plans/synthetic-template.json` is redistributable test
data for the read boundary. Its synthetic version placeholders do not certify a
valid account adoption. `StudyPlanPublicationBoundaryTest` exercises the actual
MVC/controller, file/hash allowlist and access service using temporary synthetic
files and a mocked account repository; it starts no service or database.
