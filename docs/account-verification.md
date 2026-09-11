# Account verification with Cucumber

The Java features in `src/test/atdd/` own the account HTTP assertions. Component
scenarios use the real security chain with mocked account storage; live scenarios
use PostgreSQL through an explicitly selected synthetic local API. `verify_api.py`
remains a compatibility launcher and contains no Python account assertions.

Run component and unit checks with `./mvnw test`. Run the full isolated HTTP suite:

```sh
python3 tools/accounts/cucumber_probe.py --base-url http://127.0.0.1:4322 \
  --password-file /path/to/local/synthetic-seed-password --account-guard
```

For the independently configured API demonstration on another loopback port, add
`--standalone`. This cannot target the integrated UI/API or restore-check ports.
The suite creates synthetic plans and deletes only its creations. Existing plans
are never cleared. A reported cleanup failure must be resolved before rerunning.

`--phase prepare|resume|outage|network-outage|cleanup --state-file
.codex-scratch/probe.json` uses the same CLI contract for infrastructure's lifecycle
checks. Prepare saves one synthetic plan and a private checkpoint; resume verifies
exact state after fresh login and retries a failed write once; outage verifies
stable503 errors; cleanup deletes only the owned checkpoint plan. Network-outage
also measures login under an unresponsive database and requires a retryable error
within15seconds. Infrastructure controls all stop/start/pause operations and always
restores the database. The Java helper never operates containers or volumes.

`--phase restore --base-url http://127.0.0.1:4323 --restore-file
.codex-scratch/restore-probes/inventory.json` checks ten synthetic identities and
all supplied account-owned snapshots, progress, provenance and recovery. It performs
login/read/logout only. Infrastructure separately compares database inventory before
and after, including table data, migrations, constraints, indexes and roles.

SMTP scenarios require the isolated non-relaying capture and explicit
`ATDD_LOCAL_MAIL_CAPTURE=true`, then `./mvnw -Patdd-mail -Dtest=CucumberAtddTest test`.
Use the documented live URL/password-file environment variables. The stable
synthetic support request key deliberately verifies the same receipt across
restarts; it is not a request to send external email.

Logs and lifecycle state stay in ignored `.codex-scratch/`; cookies/state are mode600.
Never publish them. Dry-run feature discovery proves step matching only. HTTP tests
do not certify frontend scheduling, Google identity, cloud networking, or production
performance. The public standalone frontend still works without this API.
