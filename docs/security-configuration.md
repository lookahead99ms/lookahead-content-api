# Security configuration ownership

Security filter chains describe the trust boundaries in Java. They are not a
configurable list of filters. `AccountSecurityConfig` owns account route policy;
`FoundationSecurityConfiguration` owns the account-free demo; `OAuthServerConfiguration`
and `OAuthResourceConfiguration` own authorization endpoints and bearer-protected API
resources; `GatewayConfiguration` owns browser sessions and the OAuth client chain.
Their profiles, matchers, chain order, PKCE, CSRF, scopes, account ownership and refresh
token rotation remain explicit security policy.

Account and gateway response handlers live separately from those chains. OAuth
client registration/persistence and signing-key/token wiring have their own
configuration classes. Gateway HTTP clients and OIDC user/decoder wiring also live
separately. This reduces the size of the classes being reviewed without removing
filters or claiming a runtime performance improvement.

## Deployment properties

`OAuthProperties` binds the existing `app.oauth` keys, including values from existing
profile YAML and Spring config-tree secret mounts. Issuer, frontend, client secret,
upstream and signing-key paths retain their existing names and meaning. Public
origins require HTTPS except explicitly configured local loopback HTTP; frontend
and issuer must match. Secrets remain in mounted files and are redacted from
`OAuthProperties` and `OAuthSettings` string representations.

The following optional duration properties have bounded startup validation:

| Property under `app.oauth` | Default | Accepted range |
| --- | --- | --- |
| `connect-timeout` | `3s` | 100 ms–10 s |
| `read-timeout` | `7s` | 100 ms–30 s |
| `authorization-code-ttl` | `1m` | 30 s–2 min |
| `access-token-ttl` | `5m` | 1–15 min |
| `refresh-token-ttl` | `8h` | 5 min–24 h, greater than access token lifetime |

For example, `app.oauth.read-timeout: 5s` adjusts the bounded gateway transport
without editing a chain. Timeout settings apply to proxy, token exchange/refresh,
JWK retrieval and user-info transport. Token lifetimes are written to the registered
client by the authorization server. Defaults preserve the previous behavior.

`AccountSecurityProperties` binds the existing `app.accounts.registration-enabled`
setting. The guarded local password-login decision remains Java policy. Session
lifetime and cookie properties continue to use Spring Boot's existing
`server.servlet.session` configuration. Request payload limits stay in their existing
filter; this refactor does not introduce new limits or relax existing ones.

## Verification

Run `./mvnw --batch-mode --no-transfer-progress verify`. Property tests exercise
binding, defaults, rejected values, origin restrictions and secret redaction. The
embedded gateway integration test assembles the extracted beans and exercises PKCE
redirect generation, invalid callbacks and 401/403 contracts. Existing embedded
account tests cover profiles, login/logout, CSRF and storage-failure responses.
Opt-in live database/OAuth lifecycle tests still require their documented isolated
stack. Passing embedded tests does not claim a complete deployed OAuth round trip.
