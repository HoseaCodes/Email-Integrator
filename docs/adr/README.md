# Architecture decision records

Decisions that were genuinely contested — where a reasonable engineer could have chosen otherwise,
and where the reasoning matters more than the outcome. Trivial choices are not recorded here;
padding this directory would make the meaningful entries harder to find.

Each record states the context, the decision, what it gained and gave up, what was rejected and why,
and the conditions under which it should be revisited.

| ADR | Decision | Status |
|---|---|---|
| [0001](0001-brevo-http-client-over-vendor-sdk.md) | Call Brevo over HTTP directly instead of using the vendor SDK | Accepted |
| [0002](0002-no-automatic-retries-on-email-send.md) | No automatic retries on email send | Accepted |

## Decisions recorded elsewhere

Some choices are documented where they are most likely to be read rather than as standalone records:

- **API keys over JWT bearer tokens** — [Security](../SECURITY.md#authentication-api-keys)
- **No circuit breaker** — [Reliability](../RELIABILITY.md#circuit-breakers)
- **CloudFront over an ALB for TLS** — [AWS architecture](../AWS_ARCHITECTURE.md)
- **One provider interface with one implementation** — [Architecture](../ARCHITECTURE.md#the-integration-boundary)
- **Everything deliberately not built** — [Roadmap](../ROADMAP.md)
