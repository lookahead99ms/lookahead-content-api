# Synthetic account API fixtures

These contain invented metadata and schedule inputs, with no curriculum bodies or
credentials. `catalog.json` is suitable for the account API's trusted catalog mount
in an isolated test runtime. Its digest identifies the deterministic JSON payload
without the `catalogVersion` property (sorted keys, compact separators).

Run the real HTTP/database checks against an already running account API:

```sh
python3 tools/accounts/verify_api.py --base-url http://127.0.0.1:4320 --password-file /path/to/ignored/seed-password
```

Add `--account-guard` when testing the final image to verify the
`X-LookAhead-Account` comparison guard against stale shared-tab identity.

The default run creates and deletes only its own test plans, including plans
returned by unexpected successful responses during rejection checks. Pass `--catalog` to
use the same metadata file mounted into an existing runtime; the driver builds
small synthetic schedules referencing that catalog and does not alter the catalog.
The default fixture uses Java and DSA so restricted learner09 can be tested.

For Infrastructure's restart/backup checks, use `--phase prepare --state-file
.codex-scratch/account-probe.json`, perform the independently coordinated lifecycle
operation, then run `--phase resume` with that state file. `--phase outage` expects
an authenticated save to return 503 and retains the same idempotency key for the
next resume. It requires a still-live session from prepare. `--phase cleanup`
deletes only the IDs listed in that state file. Session cookies are stored in a
separate mode-0600 file only for these explicit continuation phases; never commit
these generated files. The driver neither controls containers nor claims that a
restart/outage happened without external orchestration.
