# First-party OAuth and browser gateway

The `accounts,oauth-server` profiles enable the Spring Security authorization
server and bearer-only resource API. The `gateway` profile runs the confidential
OAuth client as a separate process using the same application image. This uses
Spring Security's authorization-code and OpenID Connect implementations; it does
not implement a new token protocol or require Google credentials.

The browser signs in at `/sign-in` or registers at `/sign-up`. The gateway starts
`/oauth2/authorization/lookahead`, using state, OIDC nonce and S256 PKCE. The
callback is exactly `${LOOKAHEAD_FRONTEND_ORIGIN}/login/oauth2/code/lookahead`.
Only the gateway exchanges the code, using confidential client authentication.
Access and refresh tokens stay in its server session. Browser API calls use
`/bff/api/v1/**` and an HttpOnly, SameSite=Lax session cookie. Mutations require
session-bound CSRF tokens. Secure cookies are mandatory outside explicit local
HTTP configuration.

Resource APIs ignore the identity-server session cookie. Their bearer validation
checks signature, issuer, time, API audience `lookahead-api`, registered client,
active database authorization, scope and current enabled account. Existing plan
ownership, expected-account checks and current content entitlements remain in
force. Public content is explicitly allowlisted. The gateway does not forward
browser Authorization/Cookie headers, arbitrary upstreams or upstream Set-Cookie.

Codes last one minute, access tokens five minutes and rotating refresh tokens
eight hours. Code replay and previously rotated refresh tokens are rejected.
Revocation invalidates resource access immediately through the database check.
Sign-out revokes access/refresh tokens, invalidates the gateway session and ends
the authorization-server session through OIDC logout. Public JWKS contains only
public signing material. Gateway outbound connections have a 3-second connection
and 7-second read timeout, including token exchange/refresh and signing-key fetch.

## Configuration and data

Apply API migration V5 using the existing migration role. It adds the framework's
registered-client, authorization and consent tables. Runtime credentials have
only application data privileges. Authorization rows contain sensitive tokens;
protect database access, encryption, backups and retention accordingly.

Required settings for both roles:

- `LOOKAHEAD_OAUTH_ISSUER` and `LOOKAHEAD_FRONTEND_ORIGIN`: one identical, canonical
  HTTPS origin. Loopback HTTP is accepted only for explicit local deployment.
- Secret `app.oauth.client-secret`: at least 32 random characters, loaded through
  the existing config-tree secret mount. Never put it in a frontend environment.
- Authorization server: PKCS8 private and X509 public RSA PEM files at
  `/run/secrets/oauth-signing-private.pem` and `oauth-signing-public.pem`; the
  matching key must have at least 3072 bits. Keep it stable across restarts.
- Gateway: `LOOKAHEAD_API_UPSTREAM`, a fixed internal API origin.

The platform infrastructure repo owns local generation, mounts, container
orchestration and backups. A public deployment must route browser gateway and
identity endpoints on one origin and keep resource APIs behind that gateway's
network boundary. Publish the protected frontend build; a static private-content
bundle bypasses server entitlement checks.

## Verification and operational limits

`./mvnw test` checks host contracts. `tools/accounts/oauth_probe.py` performs real
protocol rejection, issuance, rotation, revocation and logout checks against the
isolated loopback 4332 stack; it accepts secret-file paths and never logs tokens.
The private content repo owns browser evidence. No cloud deployment is implied.

CORS is a browser rule, not authentication of frontend source code. OAuth and a
confidential gateway prevent anonymous/direct cookie access to protected APIs;
they cannot prevent an authorized user from automating their own session. Browser
code cannot hold a client secret. Public endpoints remain public by design.

Gateway and identity sessions are currently in process: restarting requires
sign-in, and multiple replicas require shared session storage. Signing-key
rotation requires a planned overlapping verification-key rollout; replacing the
single configured key invalidates prior tokens. Production activation also needs
TLS, managed secret delivery, network routing, rate limits and environment checks.
Google federation is a separate integration; first-party OAuth does not implement
Google sign-in. The standalone API and public sample frontend remain independent
of this optional account deployment mode.

## JDBC connection ownership

Authorization persistence operations run in a JDBC transaction. The framework authorization row mapper performs a registered-client lookup; nested lookups must reuse the transaction-bound connection instead of retaining one connection while waiting for a second from a bounded pool. The regression suite covers a one-connection nested lookup and rollback. The local OAuth probe includes concurrent authenticated reads.

An OAuth-profile filter maps database/transaction availability failures raised before MVC to503 `ACCOUNT_STORAGE_UNAVAILABLE`, without exposing storage details. Authentication or scope failures retain their own security responses; increasing pool size is not a substitute for correct connection ownership.

Published content HTML can be framed by the same application origin only (`X-Frame-Options: SAMEORIGIN`, CSP `frame-ancestors 'self'`). Other gateway routes deny framing. This supports the existing sandboxed teaching visuals while keeping authorization and the publication allowlist intact; it does not permit arbitrary external embeds.
