# Support feedback

POST `/api/v1/support` requires an authenticated account, the current CSRF token,
and a 16–80 character `Idempotency-Key`. JSON fields are `type` (feedback/problem/
question), `subject` (160 characters), `message` (5,000), `replyTo`, and `images`
(an optional array of `{name,data}` where data is base64). At most three PNG/JPEG
images are accepted: 2 MiB each, 5 MiB combined, 16 million pixels per image.
The API checks decoded content and re-encodes images as PNG, removing original
metadata and replacing filenames. The JSON request boundary is 8 MiB.

Configure `spring.mail.host`, `spring.mail.port`, `app.support.from`, and
`app.support.inbox` in the environment. SMTP connection/read/write timeouts are
bounded. For an external provider enable authenticated TLS with server identity
verification and secret-backed credentials. No sender/inbox or paid mail provider
is selected or activated automatically. An unconfigured sender returns 503.

For local mail capture use the infrastructure repository's
`python3 scripts/mail_capture.py --port 4325`; run a host-based API with
`SPRING_MAIL_HOST=127.0.0.1`, `SPRING_MAIL_PORT=4325` and `.test` sender/inbox
addresses. This capture never relays externally. Containerized integration needs
a private-network capture service; do not expose a public SMTP port or change the
development API just to enable mail.

202 means the SMTP server accepted the message, not inbox delivery. The response
contains an opaque reference and `accepted` status. An ambiguous SMTP failure
returns 503 with the reference and `unconfirmed`; replaying that request does not
send a second message. A different payload under the same key returns409.

Receipts and the five-messages-per-account/hour limit are durable PostgreSQL records
introduced by migration V2. They serialize same-account dispatch reservations before
SMTP; accepted receipts survive API restarts and are shared across replicas. A
lost SMTP acknowledgement remains unconfirmed and is not resent automatically.
This is at-most-once application dispatch, not exact-once inbox delivery. Receipts
store a digest, status and reference; no feedback body or attachment. Provider/inbox
and local capture retention are separate. Ordinary local shutdown preserves captures.
The request must include `X-LookAhead-Account` matching the session so a pending
message cannot be retried under a different signed-in account.
