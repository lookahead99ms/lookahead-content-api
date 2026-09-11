# Email account registration

Email/password registration is explicitly enabled with `LOOKAHEAD_REGISTRATION_ENABLED=true` in an account-enabled service. It defaults to false. The local Compose account stack enables it; DEV/PROD configuration remains opt-in. Synthetic credentials still require the guarded `local-test` profile and local deployment environment, even when regular email/password login is enabled elsewhere.

`GET /api/v1/auth/options` reports `registration` and `google` availability. Google remains false pending the separately authorized OIDC implementation and provider configuration. No Google authentication or profile completion is claimed here.

`POST /api/v1/auth/register` accepts JSON with `firstName`, `lastName`, `email`, `password`, `confirmPassword`, and `countryCode`. The country is an explicit ISO 3166-1 alpha-2 choice representing country of residence; there is no geolocation or IP fallback. Passwords must match and contain 12–128 characters. Names accept Unicode and exclude control characters. Email is normalized for case-insensitive login and uniqueness. The profile is stored in API-owned migration V4. Registration is transactional and grants no paid topics.

Obtain `/auth/csrf` first and send its token header. Registration returns 201 with the existing account view, establishes an authenticated server session, changes the session ID, and invalidates the old CSRF token. Refresh the token before later mutations. The response never contains password fields and uses `Cache-Control: no-store`. Duplicate identity returns 409; invalid registration fields return 422; absent/invalid CSRF returns 403; database failures retain the safe retryable 503 behavior. Registration JSON is limited to 8 KiB.

Passwords are stored using Spring Security's supported PBKDF2 v5.8 encoder identifier and verified with the shared delegating encoder. Password confirmation is never persisted. The email is user supplied and is not marked verified by registration; no verification email is sent by this local slice. Automatic linking to Google or another account by email is not implemented.

Local checks include registration validation, credential hashing, no paid grant creation, session and CSRF rotation, logout, case-insensitive repeat login, and preservation of existing account/plan scenarios. Live verification uses generated `example.test` identities, never real email delivery.
