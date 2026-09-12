# Local author execution adapter

The account API forwards a bounded Two Sum job to a separately operated execution
broker. It does not compile, run, spawn processes or access a container engine.
The broker owns its fixtures, harness versions, queue, job ownership and execution
isolation. This capability is for the explicitly enabled local author account;
it is not a subscription entitlement or a production runner.

## Enablement

The default `app.execution.enabled` is `false`. An authorized local author receives
`ApiResponse.data` containing `schemaVersion: execution/v1`, `enabled: false`, an
empty `problems` list and `limits.sourceBytes: 65536` without contacting a broker.

Enabling requires all of the following existing local conditions:

- `accounts` and `local-test` profiles, with no `prod` or `production` profile.
- `app.deployment-environment: local` and `app.local-test.author-enabled: true`.
- `app.execution.enabled: true`.
- `app.execution.broker-origin` set to an HTTP origin with an explicit port, using
  `127.0.0.1`, `localhost` or `host.docker.internal`. Paths, credentials, queries and
  fragments are rejected. For a containerized API the configured origin may be
  `http://host.docker.internal:4340` when the documented local bridge is available.
- `app.execution.broker-token` supplied through the existing mounted secret/config-tree
  mechanism. Supply 32–512 visible ASCII characters; do not place a real value in YAML,
  source, logs or an image. The property object's string representation is redacted.

This configuration does not start a broker, enable a network listener, configure a
bridge, provision a VM or authorize an API service restart.

## API contract

Every endpoint checks the server's `LocalAuthorAccess.allowed` capability and requires
`X-LookAhead-Account` to match the current authenticated principal UUID. Ordinary
users cannot obtain execution access by changing browser flags or account headers.
Existing session/gateway CSRF protection applies to unsafe requests. OAuth API requests
require the existing `account` scope; the API still independently verifies author access.

| Method and API route | Fixed broker route |
| --- | --- |
| `GET /api/v1/executions/capabilities` | `GET /v1/capabilities` |
| `POST /api/v1/executions/jobs` | `POST /v1/jobs` |
| `GET /api/v1/executions/jobs/{uuid}` | `GET /v1/jobs/{uuid}` |
| `DELETE /api/v1/executions/jobs/{uuid}` | `DELETE /v1/jobs/{uuid}` |

Browser requests use the existing `/bff` prefix through the gateway. Its proxy permits
only the explicit execution methods/routes and rejects execution query strings.
Submission requires a UUID `Idempotency-Key` and exactly five string fields:
`problemId`, `language`, `source`, `contractVersion`, `fixtureVersion`. The current
allowlist is `algorithmic-two-sum`, `java`/`python`/`go`, `two-sum/v1`, and `visible/v1`.
Source is limited to 64 KiB UTF-8; request and broker-response bodies are limited to
512 KiB. Job identifiers must be canonical UUID-shaped values.

The API creates the broker bearer header from its configured secret and sets
`X-Execution-Owner` from the trusted account principal. It never relays browser
Authorization, Cookie, owner headers or arbitrary URLs. Successful broker objects
must declare `execution/v1` and are returned inside `ApiResponse.data`. The transport
uses a 2-second connection timeout and a 10-second whole-exchange deadline, including
slow response bodies; redirects are disabled.

Known missing/conflict/size/busy errors retain 404/409/413/429 with fixed safe messages.
Upstream authentication, malformed response, connection, timeout and other failures
become 503. Upstream error bodies, addresses, source and secret values are not exposed.

## Verification

`ExecutionAdapterTest` exercises trusted-header forwarding to a real local HTTP stub,
request/response limits, disabled/prod guards, error mapping and a slowly dripping
response deadline. `ExecutionGatewayRouteTest` checks the exact forwarding allowlist.
`ExecutionSecurityIntegrationTest` covers the actual account filter chain, author
capability, account-change check and CSRF. A real isolated broker/runner and a complete
browser round trip require the separate local deployment checks; stub results do not
certify code-execution isolation or production readiness.
