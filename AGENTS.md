# Instructions for AI coding agents

Rules for modifying this repository. They exist because this service sends email — a side effect
that reaches real people and cannot be undone — and because several of the defects fixed here came
from plausible-looking code that nobody verified.

Read [docs/ENGINEERING_AUDIT.md](docs/ENGINEERING_AUDIT.md) before substantial changes. It records
what was wrong and why, and repeating a fixed finding is worse than the original.

---

## Architecture

Respect the existing layers. Do not bypass them.

```
Controller  →  validate the wire contract, translate to domain types. No business rules,
               no provider knowledge, no try/catch.
Service     →  application decisions: sending identity, kill switch, orchestration.
               Must not import provider types or reference HTTP status codes.
Provider    →  all vendor-specific concerns: wire format, auth, timeouts, status
               interpretation. Exposes only domain types and EmailProviderException.
```

- **Provider types stay in their package.** `com.hoseacodes.emailintegrator.brevo.wire` must not
  appear in a controller or service signature. A previous version had
  `EMSBatchResponse extends EmailResponse`, which made the vendor's JSON part of this service's
  public API contract.
- **Request DTOs are not domain types.** The DTO is the published contract; the domain type is free
  to change. Keep them separate even when they look similar.
- **The `EmailProvider` interface is an error-translation boundary**, not provider anticipation. Do
  not add a factory, registry, or strategy selector. Do not add a second implementation unless a
  second provider is genuinely being integrated.

## External integrations

Any new outbound call **must** have:

- **An explicit connect and read timeout.** No exceptions. A call without one is an unbounded
  resource commitment to a system we do not control, and it can starve the servlet worker pool
  until the service stops answering every endpoint including health.
- **Failures translated at the boundary** into `EmailProviderException` with the correct
  `Reason`. Do not let a vendor exception type escape upward.
- **A `Reason` whose `sideEffectPossible` value is correct.** This is the most important field in
  the codebase. If you cannot prove the request never reached the provider, it is
  side-effect-possible. Default toward caution: a missing email is cheaper than a duplicate one.

**Do not add retries.** Sending email is not idempotent, and there is no idempotency key. This
includes retries you did not write:

- Check whether the HTTP client library enables them by default. Apache HttpClient 5 does, and it
  honours `Retry-After` on 429 and 503. This shipped undetected once already
  ([ADR 0002](docs/adr/0002-no-automatic-retries-on-email-send.md)).
- Do not add Resilience4j, `@Retryable`, or a circuit breaker without reading
  [docs/RELIABILITY.md](docs/RELIABILITY.md) and stating what problem it solves that timeouts do
  not.

If retry logic is ever justified, only `CONNECT_FAILED` is unambiguously safe, and it needs
backoff, jitter, and a cap.

## Security

Never:

- **Hardcode a credential**, including as a "temporary" value or a default. There is no default API
  key, Brevo key, or JWT secret, and the application deliberately fails to start without them. Do
  not add defaults to make local startup easier.
- **Log a secret.** No API keys, JWTs, provider credentials, `Authorization` or `X-API-Key` header
  values, at any level. The old Brevo delegate printed the API key to stdout on every send; on
  Elastic Beanstalk that reached CloudWatch, and log retention outlives credential rotation.
- **Weaken authentication to make a test pass.** `EmailControllerTest` and
  `UserApprovalControllerTest` use `@AutoConfigureMockMvc(addFilters = false)`, and that is only
  acceptable because `ApiKeySecurityTest` asserts the real rules against the real filter chain —
  including that those same endpoints return 401 without a key. If you disable filters anywhere
  else, the corresponding rules must be asserted somewhere that actually runs them.
- **Add a `permitAll()` rule** without stating in a comment why that endpoint cannot carry a
  credential. There are currently three, each justified in `SecurityConfig`.
- **Accept a sender address from a caller.** `EmailDraft` has no `sender` field on purpose — the
  invariant lives in the type, not in a check.

When substituting caller input into an email template, use the existing two-map API: text values
are HTML-escaped, link values are validated by `LinkSanitizer` first. Escaping alone leaves
`javascript:alert(1)` a valid `href`. HTML is not one context.

## Testing

Every behaviour change needs tests. For integration changes, **failure paths are not optional** —
the happy path is exercised constantly by hand; the 429-at-3am path is exercised once, in
production, unless it is tested.

- New provider interactions need coverage of 4xx, 5xx, timeout, and connection failure, asserting
  both the classification and `sideEffectPossible`.
- Use WireMock against a real socket rather than mocking the HTTP client. Mocking the client only
  asserts that the code calls methods it obviously calls.
- **No test may contact a live provider or send real email.** This also keeps CI safe on fork pull
  requests.
- Assert negatives where behaviour could be invisible. "Exactly one HTTP request" is what caught
  the automatic-retry defect.
- If a test disables a safety mechanism, verify the replacement coverage is real — the security
  suite was checked by mutation (flipping `authenticated()` to `permitAll()` must fail it).

## Verification

Claims must be checked, not asserted. Reading generated code is not verification.

- Run `./mvnw clean verify` before claiming a change works.
- For startup, configuration, container, or deployment changes, **run the thing**. Fail-fast
  behaviour was confirmed by running the packaged jar with the variable unset; the Dockerfile by
  building and running the image; the CI workflow against a clean clone with an empty environment.
- Assumptions about the environment must be checked. A health check was once written around
  "the JRE image has no curl" — the image has curl, and `/bin/sh` is dash, so the alternative would
  not have worked either.

## Documentation

Update documentation when public behaviour or architecture changes. Specifically:

- **Every claim must be backed by code.** The original README claimed Thymeleaf (never a
  dependency) and "JWT Authentication: Implemented and secure" (there was no authentication). If a
  capability is not implemented, it belongs in Known Limitations.
- **Never fabricate** users, volumes, latency, uptime, benchmarks, or coverage percentages. None
  appear in this repository, deliberately.
- Do not add a CI badge for a workflow that does not exist, and do not imply a passing build means
  the application is secure.
- `ENGINEERING_AUDIT.md` is a point-in-time snapshot and is **not** rewritten as fixes land —
  update its status table instead.
- Check cross-references resolve. Several files link to `docs/RELIABILITY.md`; a dangling link in
  otherwise consistent documentation is a real defect.

## Dependencies

Do not add a library without justification. State what problem it solves and what was considered
instead.

- Prefer removing a dependency to adding one. The Brevo SDK was removed because it used global
  static state, offered no timeout configuration, and pulled Maven 2.0.6 build tooling onto the
  runtime classpath.
- **Declare what you depend on.** `httpclient5` is a direct dependency specifically so its defaults
  are a decision rather than an inheritance from Spring Cloud Vault's tree.
- Do not blanket-upgrade. Upgrade deliberately, one at a time, running the suite after each.

## Human accountability

AI-generated code in this repository is reviewed, tested, and run before it is trusted. Agents
working here should:

- **Flag uncertainty instead of inventing requirements.** If the correct behaviour is unclear —
  particularly around retry semantics, security rules, or anything with a side effect — ask.
- **Report failures accurately.** If tests fail, say so with the output. If a step was skipped, say
  which. Do not describe intended behaviour as verified behaviour.
- **Distinguish analysis from description.** `RELIABILITY.md` separates what is implemented from
  what is design discussion for exactly this reason.
- **Prefer the smallest change that solves the problem**, and explain the trade-off rather than
  silently picking one.

The standing preference, when they conflict:

> simple → explicit → tested → observable → secure → explainable
>
> over: complex → impressive-looking → difficult to defend
