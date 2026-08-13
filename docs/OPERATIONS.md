# Operations

Running the service, and what to look at when it misbehaves. Referenced from `SecurityConfig` and
the audit.

Reliability behaviour — timeouts, retry semantics, idempotency — is in
[Reliability](RELIABILITY.md). This page is about operating the thing.

---

## Health

| Endpoint | Auth | Returns |
|---|---|---|
| `GET /actuator/health` | none | `{"status":"UP"}` |
| `GET /actuator/info` | API key | Build info |

Health is deliberately public: a platform health check cannot present credentials. It returns
**aggregate status only** — `management.endpoint.health.show-details=when-authorized`, so component
detail never renders for an anonymous probe.

!!! warning "Health does not probe the mail dependency"
    The service reports `UP` whether or not SMTP or Brevo is reachable. Health currently answers
    "is the process serving requests", not "can it deliver email". A real `HealthIndicator` for the
    mail dependency is on the [roadmap](ROADMAP.md).

    Practically: **a green health check is not evidence that sending works.** Watch the logs for
    send outcomes instead.

### Readiness versus liveness

Not separately exposed. Spring Boot's probes (`management.endpoint.health.probes.enabled`) are off.

The distinction — liveness meaning "restart me", readiness meaning "stop sending me traffic" — needs
something able to act on it. On a single-instance deployment with no load balancer, there is nothing
to remove the instance from, so the distinction would be decorative. It becomes real with the
move to a load-balanced environment.

---

## Logs

Structured SLF4J, written to stdout. On Elastic Beanstalk the platform captures stdout and can
stream it to CloudWatch Logs.

### What gets logged

Every integration call produces one line with operation, outcome, duration, and **recipient counts
rather than addresses**:

```
INFO  c.h.e.brevo.BrevoEmailProvider : Brevo send succeeded: recipients=1 variants=0 messageIds=1 durationMs=284
```

Failures carry the classification and — critically — whether the send may have happened anyway:

```
WARN  c.h.e.brevo.BrevoEmailProvider : Brevo send failed: reason=TIMEOUT sideEffectPossible=true durationMs=10014 detail=no response from Brevo within the read timeout (PT10S)
```

`sideEffectPossible=true` is the field to notice. It means the message may already have been
delivered, so re-driving the request risks a duplicate.

### Correlation

Every error response carries an `errorId` that also appears in the server log line for that failure:

```json
{ "status": 502, "code": "PROVIDER_UNAVAILABLE", "errorId": "a3f21c9e" }
```

A caller quotes the id; you find the line. The detail stays server-side.

!!! note "Successful requests are not correlated"
    There is no request-scoped correlation id in MDC yet, so successful requests cannot be traced
    end to end — only failures carry an id. On the [roadmap](ROADMAP.md).

### What is never logged

At any level: API keys, JWTs, provider credentials, `Authorization` and `X-API-Key` headers,
message bodies, recipient addresses.

Provider error **codes** are logged; provider error **messages** go to DEBUG only, because they can
echo recipient addresses and account identifiers.

### Development versus production

| | Development | Production |
|---|---|---|
| `logging.level.com.hoseacodes` | `debug` | `info` |
| Provider error bodies | visible at DEBUG | not emitted |
| `mail.debug` | keep `false` | **must** stay `false` |
| Destination | console | stdout → CloudWatch |

!!! danger "Never enable `mail.debug` outside a local investigation"
    JavaMail's debug output prints the entire SMTP conversation, including the `AUTH` command — and
    therefore the credentials.

Log level is the main thing that should differ between environments. Everything else is identical
by design, so what you debug locally behaves the way production does.

---

## Metrics

**Not implemented.** Micrometer is on the classpath via the actuator starter, but no metrics
endpoint is exposed and no custom meters are registered.

Stated plainly rather than implied: there is currently no way to answer "what is the send failure
rate?" without reading logs.

The small set worth adding — and no more — is in the [roadmap](ROADMAP.md): a counter for send
attempts tagged by provider and outcome, a timer for provider latency, and a counter for
authentication failures. Those answer *is it working*, *is it slow*, and *is someone probing us*.
Dozens of vanity gauges answer nothing.

### Counter, gauge, or timer

Worth being precise about, since picking wrong makes a metric useless:

- **Counter** — monotonically increasing, for things that happen. Send attempts, failures, auth
  rejections. You query the *rate*.
- **Gauge** — a value that goes up and down, sampled. Queue depth, active connections. Nothing here
  needs one.
- **Timer** — duration plus a count, giving percentiles. Provider latency. A mean would hide the tail,
  and the tail is what hurts.

---

## Tracing

Not implemented. There is one service and two outbound dependencies, so a trace would show little
that the existing per-call duration logging does not.

It earns its place when a request crosses several services and the question becomes "which hop was
slow". At that point Micrometer Tracing with an OTLP exporter is the path — Spring Boot 3
auto-instruments the servlet layer and outbound clients, so the code change is small and the work is
in running a collector.

Deliberately not added to match a job description.

---

## Troubleshooting

### The application will not start

Fail-fast configuration validation is working as intended. The error names the property and how to
fix it.

```
Property: brevo.api-key
Reason: brevo.api-key must be set (env BREVO_API_KEY); it has no default
```

Required: `API_KEY_DEFAULT`, `BREVO_API_KEY`, `JWT_SECRET`, `MAIL_PASSWORD`. `JWT_SECRET` and
`API_KEY_DEFAULT` must be at least 32 characters. See `.env.example`.

This is preferred to starting successfully and failing every request — the failure appears in a
deployment log immediately rather than in a support ticket days later.

### Every request returns 401

The `X-API-Key` header is missing or wrong. The response body does not distinguish the two on
purpose, since that difference is useful mainly to someone probing for valid credentials. Check the
server log:

```
WARN  c.h.e.security.ApiKeyAuthenticationFilter : Rejected invalid API key for POST /email
```

A key was presented and did not match. No such line means no key was sent at all.

### Sends return 502 with `PROVIDER_AUTH_FAILED`

**Our** provider credentials are wrong, not the caller's — which is why this is 502 and not 401.
Check `BREVO_API_KEY` or `MAIL_PASSWORD`. A rotated Gmail app password that was never redeployed is
the usual cause.

### Sends return 503 with `EMAIL_SENDING_DISABLED`

`app.email.enabled=false`. Intended for exercising the full request path without delivering mail. If
this is unexpected, check `APP_EMAIL_ENABLED` in the environment.

### Sends return 504 with `deliveryUncertain: true`

The provider did not respond in time. **The message may have been sent anyway.**

Do not simply re-drive the request — there is no idempotency key, so a retry can duplicate a real
email. Instead, take the `messageId` from the log line and check the provider's dashboard or the
mail server logs before deciding.

```
WARN ... reason=TIMEOUT sideEffectPossible=true messageId=<1a2b3c@example.com> ...
```

The `Message-ID` is assigned **before** transmission precisely so it exists in this case.

### Latency has risen

The per-call `durationMs` in the integration log line is the first place to look — it separates
"the provider got slower" from "we are queueing". Without metrics this is log analysis; a timer with
percentiles would answer it directly.

If provider latency is genuinely elevated, the risk is worker-pool exhaustion: with a 10s read
timeout, enough concurrent slow calls will occupy every Tomcat worker. Timeouts bound the damage but
do not remove it.

### Approvals happening that nobody clicked

Known issue. `GET /auth/approve` changes state, and mail clients, security scanners, and link
prefetchers routinely fetch URLs found in messages — Outlook Safe Links does this by default. See
ENGINEERING_AUDIT MED-6; the fix needs single-use tokens.

---

## Operational signals worth watching

Once metrics exist, these are the ones that matter. Until then they are log queries.

| Signal | Why | What it suggests |
|---|---|---|
| Send failure rate by `reason` | The single most informative number | `PROVIDER_AUTH_FAILED` → credentials; `CONNECT_FAILED` → provider down |
| Count of `sideEffectPossible=true` | Each one is a message of unknown status | Sustained non-zero means real delivery ambiguity |
| Provider latency p95/p99 | Precedes worker exhaustion | Rising p99 with flat p50 → provider degrading |
| Authentication failure rate | Credential probing | A burst from one source is an attack |
| `RATE_LIMITED` count | Approaching provider quota | Sustained → need throttling upstream |

---

## Deployment and rollback

Deployment is `./eb-deploy.sh`. It validates required secrets **before** uploading anything, applies
them as environment properties, then deploys a versioned bundle and polls `/actuator/health`.

**Rollback** is redeploying a previous version label — Elastic Beanstalk retains prior versions in
S3. On a single instance that means downtime for the duration, and **nothing rolls back
automatically**: a failed health check does not trigger one. A load-balanced environment with
rolling deployments would make this a non-event.

Full deployment detail, IAM, and the TLS gap: [AWS architecture](AWS_ARCHITECTURE.md).

---

## Capacity

No load testing has been performed. **No throughput, latency, or capacity figure appears anywhere in
this repository**, because none has been measured.

Reasoning about what would break first, in order: CPU credits on the burstable `t3.micro`, then the
Tomcat worker pool if the provider slows, then Gmail's daily send limit — which is a hard quota
rather than a soft degradation.
