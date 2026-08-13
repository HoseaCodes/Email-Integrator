# Reliability

How this service behaves when the things it depends on misbehave, and — just as important — what
it deliberately does not do.

Everything under [What is implemented](#what-is-implemented) is in the code and covered by tests.
Everything under [What is not implemented](#what-is-not-implemented) is analysis, not description.
The two are kept separate on purpose.

---

## The problem this document exists for

**Sending email is not idempotent.** There is no natural key the provider deduplicates on, so two
identical requests produce two messages in a person's inbox. That single fact drives almost every
decision below, because it removes the tool most services reach for first: the retry.

For a `GET`, a retry is free — worst case you fetch the same bytes twice. For a send, a retry is a
second email to a real human, and if the operation was a password reset or an invoice, a
confusing or alarming one.

---

## What is implemented

### Timeouts on every outbound call

No network call can block indefinitely.

| Path | Connect | Read | Write | Configured in |
|---|---|---|---|---|
| Brevo HTTP API | 3s | 10s | — | `brevo.connect-timeout`, `brevo.read-timeout` |
| Gmail SMTP | 5s | 10s | 15s | `app.mail.connection-timeout`, `.read-timeout`, `.write-timeout` |

**Why this comes before everything else.** Tomcat serves requests from a bounded worker pool. A
call with no timeout holds its worker until the socket gives up, which for a hung TCP connection
can be minutes. Under sustained slowness the pool drains and the service stops answering *every*
endpoint — including `/actuator/health`, at which point the platform health check fails and the
instance is replaced with in-flight work still on it.

The counter-intuitive part worth internalising: **a slow dependency is more dangerous than a dead
one.** A dead dependency fails fast and returns the worker. A slow one holds resources while still
looking alive. Timeouts convert the slow case into the fast case.

The SMTP write timeout is separate and higher because transmitting a large HTML body legitimately
takes longer than waiting for a command response — one budget for both would either cut off large
messages or make command waits far too generous.

### No retries, anywhere

Neither send path retries, and the HTTP client's automatic retries are explicitly disabled.

This was not free. Apache HttpClient 5 enables `DefaultHttpRequestRetryStrategy` **by default**: it
re-sends on transient I/O failures, and on HTTP 429 and 503 it honours the provider's
`Retry-After` and sleeps before trying again. Nothing in application code mentioned retries, and
the client had arrived transitively through Spring Cloud Vault — so the retry policy for
non-idempotent email was being set by an unrelated dependency's dependency tree.

It surfaced as a hung test suite: a stub advertising `Retry-After: 42` made the client sleep 42
seconds and re-send. `BrevoClientConfig` now calls `.disableAutomaticRetries()`, `httpclient5` is a
declared dependency rather than an inherited one, and tests assert exactly one HTTP request per
send for 429, 500, and 503. Full write-up in
[ADR 0002](adr/0002-no-automatic-retries-on-email-send.md).

Redirect following is disabled too — a redirect on a POST would re-issue the send against a
location we did not choose.

### Failures classified by whether the email may already have been sent

The core of the design. `EmailProviderException.Reason` records, per failure, whether a side effect
may have occurred:

| Failure | Reason | Reached the provider? | Side effect possible |
|---|---|---|---|
| Connection refused, DNS failure, connect timeout | `CONNECT_FAILED` | No | **No** |
| HTTP 400 rejected | `REQUEST_REJECTED` | Yes, and refused | No |
| HTTP 401 / 403 (our key) | `PROVIDER_AUTH_FAILED` | Yes, and refused | No |
| HTTP 429 rate limited | `RATE_LIMITED` | Yes, and refused | No |
| HTTP 5xx | `PROVIDER_UNAVAILABLE` | Yes — may have queued first | **Yes** |
| Read timeout | `TIMEOUT` | Yes — response lost | **Yes** |
| SMTP send failure | `PROVIDER_UNAVAILABLE` | Partially — SMTP is not atomic | **Yes** |

Two classifications deserve their reasoning stated:

- **A read timeout is side-effect-possible.** The request was written; the response never arrived.
  The provider very likely processed it. This is the single most dangerous failure to retry
  blindly.
- **An SMTP send failure is side-effect-possible.** `MailSendException` explicitly models
  per-recipient failures, so the server may already have accepted the message for some recipients
  before the error was raised.

Classification defaults toward caution: an unrecognised transport failure is treated as
*possibly delivered*, because the cost of being wrong in that direction is a missing email, while
the other direction is a duplicate one.

**This reaches API callers.** A failure where delivery is uncertain returns `deliveryUncertain: true`
in the error body, so a client can decide rather than guess:

```json
{
  "timestamp": "2026-08-13T09:14:22Z",
  "status": 504,
  "code": "PROVIDER_TIMEOUT",
  "message": "The email provider did not respond in time. Delivery status is unknown; do not retry without an idempotency key.",
  "path": "/email",
  "errorId": "a3f21c9e",
  "deliveryUncertain": true
}
```

### Message identity available even when a send fails

Both SMTP paths call `saveChanges()` to assign the `Message-ID` **before** transmitting, rather
than reading it back afterwards. On a timeout — precisely when delivery is unknown — there is
still an identifier to search the mail server's logs with. Reading it back after a failed send
would yield nothing, which is exactly when it is most needed. It is logged on the failure path.

### Fail-fast configuration

The application refuses to start without an API key, a Brevo key, or a JWT signing key of adequate
length. A service that will fail every request is better off not accepting traffic: the failure
shows up in a deployment log immediately rather than in a support ticket days later.

### A kill switch

`app.email.enabled=false` makes every send return `503 EMAIL_SENDING_DISABLED` while still
exercising authentication, validation, mapping, and error handling. Deliberately an explicit
failure rather than a silent no-op — a caller told "accepted" for a message that will never be
sent has been misinformed.

---

## What is not implemented

### Idempotency

**There is none.** A duplicate submission, or a client retry after an uncertain failure, sends a
second email. `deliveryUncertain` tells a caller when that risk applies; nothing prevents it.

The dangerous window is specific and worth naming:

```
Client ──POST /email──▶ Service ──▶ Provider  ✅ accepted, queued
                                        │
                          ✗ response lost / read timeout
                                        │
Client ◀── 504 deliveryUncertain ───────┘

The client cannot distinguish "not sent" from "sent, response lost".
A naive retry sends the message twice.
```

**How this would be solved.** In roughly increasing cost:

1. **Client-supplied idempotency key.** The caller sends `Idempotency-Key: <uuid>`; the service
   records key → outcome and replays the stored response for a repeat within a TTL. This is the
   Stripe model and it is the right first step here. For a single instance an in-memory cache with
   a documented TTL would be honest and testable; it would be correct *only* for the current
   single-instance deployment, and that limitation would have to be stated rather than implied
   away.
2. **Shared idempotency store.** The moment there is more than one instance, the cache has to move
   to something shared — Redis or a database table with a unique constraint on the key. The
   interesting part is the race: two concurrent requests with the same key must not both proceed,
   which needs an atomic insert-or-fail, not a check-then-act.
3. **Provider-side deduplication.** Some providers accept a client-supplied idempotency token and
   deduplicate server-side, which is strictly better because it survives our process dying
   mid-send. Whether Brevo supports this for transactional sends would need checking before it
   could be claimed.
4. **Transactional outbox.** Persist the intent, return `202`, and let a background worker own
   delivery with full knowledge of what has already been attempted. This is the robust answer and
   also the largest change — it turns a synchronous API into an asynchronous one and introduces a
   datastore this service currently does not have.

**Why not built here.** An idempotency mechanism that is correct on one instance and silently
wrong on two is a liability in a portfolio: it looks like the problem is solved. Naming the
problem precisely and describing the real solutions is more honest than a half-implementation.

### Single-use approval links

`GET /auth/approve` and `/auth/deny` change state and send mail. `GET` is required to be safe, and
this is not academic for a link delivered by email: mail clients, security scanners, link-expansion
previews, and browser prefetchers routinely fetch URLs found in messages. Outlook Safe Links does
this by default. Any of them can trigger an approval the recipient never clicked.

Two fixes, both needing more than a code change:

- **`GET` renders a confirmation page, `POST` performs the action.** Defeats prefetch entirely and
  is the conventional answer.
- **Single-use tokens.** Add a `jti` claim and a store of consumed ids, checked on verification.
  This overlaps directly with idempotency — both need server-side state keyed by a token.

Neither is implemented. Tracked as ENGINEERING_AUDIT MED-6 and documented on the controller.

### Rate limiting

None. An authenticated client can send until the provider's quota is exhausted. Do not expose this
service to untrusted clients. Authentication is what currently stands between the internet and the
send endpoints; it bounds *who*, not *how much*.

### Circuit breakers

Not implemented, and worth explaining rather than adding.

A circuit breaker stops calling a dependency that is failing, so requests fail immediately instead
of each burning a timeout. With a 10s read timeout, 200 workers, and a dead provider, every worker
spends 10s discovering the same fact — a breaker turns that into an instant failure and protects
the pool.

**When a breaker makes things worse:**

- **It fails requests that would have succeeded.** A breaker opened by a burst of errors rejects
  traffic during recovery, and if the threshold is tuned tightly it can open on a transient blip
  and amplify a small problem into a total outage of that feature.
- **Half-open probes can re-trigger the failure.** A recovering provider hit by every instance's
  probe simultaneously may fall over again.
- **On one instance it is mostly redundant.** Timeouts already bound the damage. A breaker earns
  its keep at a scale where many workers pile onto a failing dependency at once — which is not
  where this deployment is.

Adding Resilience4j now would expand the technology list without addressing a problem the service
actually has. The honest ordering is: timeouts first (done), then idempotency, then a breaker if
and when concurrency makes it matter.

### Retries, if they were ever added

If retries are reintroduced, the design is already constrained by the classification above:

- **Only `CONNECT_FAILED` is unambiguously safe.** The request never reached the provider. Every
  other failure either cannot succeed on retry or risks a duplicate.
- **`RATE_LIMITED` is retryable but not here.** Respecting `Retry-After` inside the request thread
  blocks a worker for the provider's suggested delay — which is how one throttled dependency
  becomes a service-wide outage. It belongs in a background worker, not the request path.
- **Exponential backoff**, so successive attempts wait 1s, 2s, 4s rather than hammering a
  struggling dependency.
- **Jitter**, so clients that failed together do not retry together. Without it, every caller
  synchronises on the same schedule and the provider gets a thundering herd at each interval.
- **A cap**, on both attempts and total elapsed time.

The failure mode to name is **retry amplification**: a provider returning 503 under load, retried
three times by every caller, receives four times the traffic at its worst moment. Retries convert
a partial degradation into an outage. This is why "just retry it" is not a free improvement.

---

## Failure modes

| Failure | Detection | Current behaviour | Residual risk |
|---|---|---|---|
| Provider returns 5xx | `PROVIDER_UNAVAILABLE` in logs | 502, `deliveryUncertain: true` | Caller may retry and duplicate |
| Provider returns 429 | `RATE_LIMITED` | 429 + `Retry-After` passthrough | Nothing throttles the caller |
| Provider slow | Read timeout at 10s | 504, `deliveryUncertain: true` | Workers held up to 10s each |
| Provider unreachable | `CONNECT_FAILED` | 503, safe to retry | Service is down for sending |
| Provider accepted, response lost | Indistinguishable from timeout | 504, `deliveryUncertain: true` | **Duplicate on retry.** The core unsolved case |
| Our provider credentials wrong | `PROVIDER_AUTH_FAILED` | 502 | Total sending outage until fixed |
| SMTP partial delivery | `PROVIDER_UNAVAILABLE` | 502, `deliveryUncertain: true` | Some recipients got it, some did not |
| Malformed JSON | Handler | 400 `MALFORMED_REQUEST` | None |
| Oversized payload | Bean Validation size caps | 400 before any provider call | Caps are per-field, not whole-body |
| JWT expired | Token verification | 400, generic message | Link must be reissued |
| JWT forged | Signature check | 400, logged at WARN | None |
| Config missing at startup | Fail-fast validation | Refuses to start | Deployment fails loudly — intended |
| Instance restarts | Platform health check | In-flight requests dropped | Single instance: full downtime |
| Duplicate client request | **Not detected** | Second email sent | See idempotency above |
| Link prefetcher fetches approval URL | **Not detected** | Approval applied | See single-use links above |

---

## What breaks first at 10× traffic

In order:

1. **CPU credits on the `t3.micro`.** It is burstable; sustained load exhausts credits and the
   instance throttles.
2. **The Tomcat worker pool**, if the provider slows down. Timeouts bound this but do not remove
   it — 200 workers each waiting 10s is still 200 workers unavailable.
3. **Gmail's daily send limit** on the SMTP path, which is a hard quota, not a soft degradation.

The first two need the load-balanced, multi-instance move described in
[AWS_ARCHITECTURE.md](AWS_ARCHITECTURE.md). The third needs the templated mail to move onto a
transactional provider rather than a personal mailbox.

None of this has been load tested. No throughput, latency, or capacity figure appears in this
repository, because none has been measured.
