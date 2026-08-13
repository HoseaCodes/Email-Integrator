# Roadmap

Remaining work, in the order the value actually arrives. Each item says what it costs and — more
usefully — what it *buys*, because several plausible-sounding improvements here buy very little.

Current state and what has already been closed: [ENGINEERING_AUDIT.md](ENGINEERING_AUDIT.md).

---

## Now — requires a human, not a commit

### 1. Rotate the Gmail app password · CRIT-5

The only unresolved CRITICAL, and no commit can close it.

`production-secrets-backup.txt` holds the app password for `info@ambitiousconcept.com` in
plaintext, and the file's own comments say it was recovered from shell history. It has therefore
been in at least two plaintext locations. It was **never committed** — verified against every blob
on every ref — so this is a rotation, not a history rewrite.

1. Revoke the app password: Google Account → Security → 2-Step Verification → App passwords.
2. Issue a new one and set `MAIL_PASSWORD` from it.
3. Delete `production-secrets-backup.txt`.
4. Clear the shell-history entry.

An app password bypasses 2FA entirely, which is why this outranks everything below it.

### 2. Take the public environment down, or finish item 3

`email-integrator-prod.eba-p4bnt2xm.us-east-1.elasticbeanstalk.com` served an unauthenticated open
relay until recently. Authentication now closes that, but the deployment still runs over plain
HTTP, so API keys and approval tokens cross the network in cleartext. Either deploy the current
code and add TLS, or shut the environment down until you do.

---

## Next — the items that change what the repository demonstrates

### 3. TLS via CloudFront + ACM · HIGH-7
**Cost:** ~$0 (1TB/month free tier), a few hours. **Buys:** the last blocking item before this
could carry real traffic.

Decision and trade-offs already recorded in [AWS_ARCHITECTURE.md](AWS_ARCHITECTURE.md), including
the honest caveat that the CloudFront→origin hop stays HTTP unless the origin also gets a
certificate. Set `app.base-url` to `https://` afterwards so approval links stop being emitted over
plaintext.

### 4. Idempotency keys
**Cost:** 1–2 days. **Buys:** the largest remaining engineering signal in the project.

The `deliveryUncertain` flag currently tells a caller when a retry might duplicate a message but
gives them no way to retry safely. An `Idempotency-Key` header with a stored key → outcome mapping
closes that loop.

The interesting part is not the happy path — it is being explicit that an in-memory store is
correct only for the current single-instance deployment, and saying so rather than implying
otherwise. Strategies and their costs: [RELIABILITY.md](RELIABILITY.md#idempotency).

Do this **before** item 5, because single-use tokens need the same server-side state and the two
should share it rather than growing two stores.

### 5. Single-use approval links · MED-6
**Cost:** half a day on top of item 4. **Buys:** closes a real correctness bug.

`GET /auth/approve` and `/auth/deny` change state, so a mail client's link prefetcher can trigger
an approval nobody clicked. Two options: `GET` renders a confirmation page and `POST` performs the
action (conventional, defeats prefetch entirely), or add a `jti` claim plus a consumed-token store.

### 6. Application metrics · MED-5
**Cost:** half a day. **Buys:** the ability to answer "is it working?" without reading logs.

A *small* set, not a dashboard full of vanity gauges:

- a counter for send attempts, tagged by provider and outcome;
- a timer for provider latency;
- a counter for authentication failures.

Micrometer is already on the classpath via the actuator starter. Pair with a request-scoped
correlation id in MDC, so successful requests are traceable and not only failures.

Also worth doing here: a real `HealthIndicator` that probes the mail dependency. Health currently
reports UP whether or not SMTP is reachable.

---

## Later — worth doing, lower signal per hour

### 7. Spring Boot upgrade · MED-2
3.2.5 is past its OSS support window. Upgrade one minor at a time, running the suite after each —
not a single blanket jump. Dependabot now raises these as grouped PRs.

This also unblocks **Java 21**, which would let the dispatch in `UserApprovalEmailService` become a
pattern-matching switch with compiler-checked exhaustiveness, replacing the test that currently
stands in for it.

### 8. Dependency vulnerability scanning
Deliberately excluded from CI so far, and the reasoning is in
[`.github/workflows/build.yml`](../.github/workflows/build.yml): OWASP dependency-check needs the
full NVD feed and fails for reasons unrelated to the change under review. Revisit with a scanner
that fails fast and reports precisely. Whatever is chosen, the README must not imply a passing
scan means the application is secure.

### 9. Rate limiting
An authenticated client can currently send until the provider's quota is exhausted. Authentication
bounds *who*, not *how much*. Keep it simple and testable — a per-key token bucket. This becomes
important the moment the service is exposed to clients you do not control.

### 10. Thymeleaf for templating
Would give contextual escaping by default rather than escaping applied deliberately at each
substitution, and would make the README's original Thymeleaf claim true at last. Deliberately not
bundled with the injection fix so that a regression could be attributed to one change or the other.

### 11. Load-balanced, multi-AZ Elastic Beanstalk
~$16–18/month for the ALB. Buys zero-downtime rolling deploys, instance-failure survival, and
end-to-end TLS. The point at which items 4 and 9 stop being "correct on one instance".

### 12. Secrets into SSM Parameter Store
EB environment properties are readable by anyone with console access. Parameter Store
`SecureString` is free at this scale and adds KMS encryption, a CloudTrail audit trail, and
rotation without a redeploy.

---

## Deliberately not planned

Recorded so their absence reads as a decision rather than an oversight.

| Not doing | Why |
|---|---|
| **Circuit breakers / Resilience4j** | Timeouts already bound the damage on a single instance. A breaker earns its keep when many workers pile onto a failing dependency at once — not here. It can also fail requests that would have succeeded. See [RELIABILITY.md](RELIABILITY.md#circuit-breakers) |
| **Automatic retries** | Sending is not idempotent. Revisit only after item 4, and even then only `CONNECT_FAILED` is unambiguously safe. [ADR 0002](adr/0002-no-automatic-retries-on-email-send.md) |
| **ECS / Fargate migration** | Elastic Beanstalk is adequate for one containerised service. Migrating to sound sophisticated is a worse answer than staying and explaining why. [AWS_ARCHITECTURE.md](AWS_ARCHITECTURE.md#evolution-path) |
| **Microservices, Kafka, Redis, event sourcing, CQRS** | No requirement in this project calls for any of them. Adding one would expand the technology list and shrink the amount of it that could be defended |
| **A second email provider** | The `EmailProvider` interface exists to translate errors, not to anticipate providers. A fake second implementation would be pattern-collecting |
| **Load testing figures** | Nothing has been measured, so no throughput, latency, or capacity number appears anywhere in this repository. If that changes, the method has to be published alongside the numbers |

---

## Not a goal

Finishing this list. The project is a portfolio artifact, and past a point the marginal item
demonstrates less than the judgment of having stopped. Items 1–3 are genuinely unfinished
business; 4–6 are the ones that would most change what a reviewer concludes; everything below that
is optional.
