# ADR 0002 — No automatic retries on email send

- **Status:** Accepted
- **Date:** 2026-08-07
- **Related:** ADR 0001

## Context

Sending an email is **not idempotent**. There is no natural key the provider deduplicates on, so two identical requests produce two messages in a person's inbox. A retry is therefore not a free correctness-preserving operation the way it is for a `GET`.

This came up concretely rather than theoretically. A test asserting that one send produces exactly one HTTP request **hung the entire suite for ten minutes**. A thread dump showed:

```
at org.apache.hc.core5.util.TimeValue.sleep(TimeValue.java:382)
at org.apache.hc.client5.http.impl.classic.HttpRequestRetryExec.execute(HttpRequestRetryExec.java:165)
```

Apache HttpClient 5 enables `DefaultHttpRequestRetryStrategy` **by default**. It re-sends on assorted transient I/O failures, and on HTTP 429 and 503 it honours the provider's `Retry-After` and sleeps before trying again. The test stub advertised `Retry-After: 42`, so the client slept 42 seconds and re-sent — a duplicate email, from a default nobody had chosen.

Two things made this easy to miss:

- Nothing in the application code mentioned retries.
- Spring's `ClientHttpRequestFactories.get()` selects an HTTP client by scanning the classpath. `httpclient5` was present only *transitively*, via `spring-cloud-starter-vault-config`. The outbound retry policy for email was therefore being decided by an unrelated dependency's dependency tree.

## Decision

1. **Disable automatic retries explicitly** in `BrevoClientConfig` via `.disableAutomaticRetries()`, and disable redirect handling while we are at it — following a redirect on a POST would re-issue the send against a location we did not choose.
2. **Declare `httpclient5` as a direct dependency**, so the outbound client and its defaults are a decision this project makes rather than one it inherits.
3. **Add no retry logic of our own** at this time.
4. **Record, per failure, whether the send might have happened** — `EmailProviderException.Reason.isSideEffectPossible()` — so a future retry layer has the fact it needs instead of guessing.
5. **Assert the behaviour in tests**: exactly one HTTP request per send for 429, 500, and 503, and a 429 must fail fast rather than sleeping out `Retry-After` inside the request thread.

## Why not just retry the "safe" failures now

Because "safe" is narrower than it first appears, and the classification has to exist before the retry can.

| Failure | Did the request reach the provider? | Safe to retry? |
|---|---|---|
| Connection refused / DNS failure / connect timeout | No — no connection established | **Yes** |
| HTTP 400 (rejected) | Yes, and rejected | No — it will be rejected again |
| HTTP 401/403 (our key is bad) | Yes, and rejected | No — nothing changes on a retry |
| HTTP 429 (rate limited) | Yes, and refused | Only after the indicated delay, and not inside the request thread |
| HTTP 5xx | Yes — may have been queued before the error | **No** — could duplicate |
| Read timeout | Yes — request written, no response | **No** — could duplicate |

Only the first row is unambiguously safe, and `CONNECT_FAILED` exists precisely to mark it. Retrying it is a reasonable future change; retrying anything below it is not, without an idempotency key the provider honours.

The wider failure mode worth naming: retries multiply load on a dependency that is already struggling. A provider returning 503 under pressure, retried three times by every caller, receives four times the traffic at its worst moment. That is a **retry storm**, and it converts a partial degradation into an outage. If retries are added later they need exponential backoff, jitter to stop clients synchronising, and a cap.

## Consequences

**Gained**

- No possibility of a duplicate email from a library default.
- Failures surface immediately instead of blocking a Tomcat worker for the provider's suggested delay — one throttled dependency can no longer starve the whole service.
- The retry decision is explicit, visible in one place, and covered by tests.

**Given up**

- Transient blips that a single retry would have papered over now surface to the caller as 502/503/504. This is the intended trade: the caller knows what happened and can decide, rather than us silently risking a duplicate on their behalf.
- Callers wanting at-least-once delivery must implement their own retry, guided by the `deliveryUncertain` flag in the error response.

## What would change this decision

Any of:

- An **idempotency key** accepted by this API and carried through to the provider, letting a retry be deduplicated (see `docs/RELIABILITY.md`).
- A **durable outbox**: persist the intent, return 202, and let a background worker own delivery and retries with full knowledge of what has already been attempted.
- Narrowly retrying **only** `CONNECT_FAILED`, with backoff and jitter — the one case already proven safe by classification and test.
