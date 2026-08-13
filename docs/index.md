# Email Integrator

Engineering documentation for a Spring Boot service that sends transactional email through an
external provider (Brevo) and through Gmail SMTP.

!!! info "Portfolio project — not production-ready"
    Deliberately stated up front. There is no TLS in the deployment, no idempotency mechanism, and
    no rate limiting. Every gap is listed under Known Limitations on the relevant page rather than
    omitted, and nothing in this documentation describes behaviour the code does not have.

---

## What makes it worth reading

Most email-integration examples stop at calling the provider's SDK from a `@RestController`. The
questions this one is built around:

<div class="grid cards" markdown>

- :material-email-alert:{ .lg .middle } **Sending email is not idempotent**

    ---

    So what is a retry actually worth? A retry is a second message in someone's inbox. This service
    does not retry, and [ADR 0002](adr/0002-no-automatic-retries-on-email-send.md) explains why —
    plus what it would take to make retries safe.

- :material-bug-check:{ .lg .middle } **A retry can hide in a library default**

    ---

    Apache HttpClient 5 retries automatically, honouring `Retry-After` on 429 and 503. A test
    asserting "one send, one HTTP request" hung for ten minutes and exposed it. It arrived through
    an unrelated dependency's tree.

- :material-help-rhombus:{ .lg .middle } **Which failures could have already sent?**

    ---

    A refused connection could not have. A read timeout could have. That distinction is modelled
    per failure and surfaced to callers as `deliveryUncertain`. See [Reliability](RELIABILITY.md).

- :material-shield-search:{ .lg .middle } **An audit of my own code**

    ---

    Including the findings that were embarrassing: a hardcoded credential, an API key printed to
    stdout, and an unauthenticated endpoint that would mail arbitrary content to arbitrary
    recipients. See [Engineering audit](ENGINEERING_AUDIT.md).

</div>

---

## Start here

| If you want to | Read |
|---|---|
| Understand how it fits together | [Architecture](ARCHITECTURE.md) |
| Know how authentication works and what it does not cover | [Security](SECURITY.md) |
| See the timeout, retry, and idempotency reasoning | [Reliability](RELIABILITY.md) |
| Run it, or work out why it is misbehaving | [Operations](OPERATIONS.md) |
| See what is deployed on AWS and what is missing | [AWS architecture](AWS_ARCHITECTURE.md) |
| Browse the HTTP contract | [API reference](api/index.html) |
| Know what is left and what is deliberately not planned | [Roadmap](ROADMAP.md) |

Decision records live under [Decision records](adr/0001-brevo-http-client-over-vendor-sdk.md), and
the full audit with a resolution status table is in [Engineering audit](ENGINEERING_AUDIT.md).

---

## At a glance

| | |
|---|---|
| **Stack** | Java 17 · Spring Boot 3.2.5 · Maven |
| **Security** | Spring Security, deny-by-default, per-client API keys, constant-time comparison |
| **Providers** | Brevo HTTP API · Gmail SMTP |
| **Resilience** | Explicit timeouts on every outbound call; automatic retries deliberately disabled |
| **Tests** | 161, weighted toward provider failure paths, run against a real HTTP server (WireMock) |
| **CI** | GitHub Actions on every pull request and push to `master` |
| **Deployment** | Docker on AWS Elastic Beanstalk, single instance |

Source: [github.com/HoseaCodes/Email-Integrator](https://github.com/HoseaCodes/Email-Integrator)
