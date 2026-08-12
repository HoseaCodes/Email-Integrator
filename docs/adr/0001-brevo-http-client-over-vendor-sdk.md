# ADR 0001 — Call Brevo over HTTP directly instead of using the vendor SDK

- **Status:** Accepted
- **Date:** 2026-08-07
- **Supersedes:** the `com.sendinblue:sib-api-v3-sdk` integration

## Context

The service sent transactional email through Brevo using two mechanisms at once:

- `TransactionalEmailsApi` from `com.sendinblue:sib-api-v3-sdk:7.0.0` for single sends, and
- a hand-rolled `RestTemplate.exchange()` call against `https://api.brevo.com/v3/smtp/email` for batch sends.

Both paths lived in `BrevoEmailDelegate`, authenticated differently, and produced unrelated response types. Beyond the duplication, the SDK caused four concrete problems:

1. **Global mutable authentication state.** The SDK authenticates via `Configuration.getDefaultApiClient()`, a process-wide singleton. The API key is set on shared state before each call, which is not thread-safe under concurrent requests and cannot be isolated per test.
2. **No timeout control.** The SDK owns its HTTP client and exposes no supported way to configure connect or read timeouts. Every send could block indefinitely.
3. **Build tooling on the runtime classpath.** `sib-api-v3-sdk` declares Maven's own libraries as `compile` dependencies. The deployable jar therefore contained `maven-project:2.0.6`, `maven-artifact-manager:2.0.6`, `maven-settings:2.0.6`, `maven-gpg-plugin:1.5`, `wagon-provider-api:1.0-beta-2`, and `swagger-annotations:1.5.18` — Maven 2 artifacts from 2006, shipped to production.
4. **Effectively unmaintained at that coordinate.** Sendinblue became Brevo; the `com.sendinblue` artifact has not kept pace.

The surface actually used is one endpoint: `POST /v3/smtp/email`.

## Decision

Remove the SDK. Call Brevo directly with Spring's `RestClient`, with hand-written request and response records confined to the `brevo` package, behind an `EmailProvider` interface.

Also **declare `org.apache.httpcomponents.client5:httpclient5` explicitly** rather than inheriting it, and configure it deliberately. See ADR 0002 for why that turned out to matter more than expected.

## Consequences

**Gained**

- Explicit connect and read timeouts (`BrevoClientConfig`), so no send can hang indefinitely.
- No shared mutable auth state; the API key is set per request.
- Six ancient transitive artifacts removed from the runtime classpath.
- Testable against a local HTTP server, so provider failures — 429, 5xx, timeouts, truncated bodies — are exercised in CI without touching the live API or sending real mail.
- A single mechanism for all sends, replacing the SDK/`RestTemplate` split.
- Provider wire types no longer leak into the public API. Previously `EmailInput.batchInput` accepted Brevo's request shape and `EMSBatchResponse extends EmailResponse` returned it, which made Brevo's JSON part of this service's published contract.

**Given up**

- We now own the request/response mapping and must track Brevo API changes ourselves. For one endpoint with a stable schema this is a small, contained cost.
- No SDK-provided helpers for endpoints we do not use.

**Not chosen**

- *Keep the SDK and exclude the Maven artifacts.* Fixes the classpath but not the global auth state or the missing timeouts, which were the more serious problems.
- *Keep the SDK and wrap it behind the interface.* The interface would hide the vendor types, but a caller could still not configure a timeout — and an integration whose failure modes cannot be bounded is the specific thing this project is meant to demonstrate handling.

## When this decision should be revisited

If Brevo usage expands beyond a couple of endpoints — contacts, campaigns, webhook verification, event polling — the balance tips back toward a maintained SDK. The rule of thumb: hand-rolled HTTP is right for a *small, stable* surface; an SDK earns its keep on a *broad or fast-moving* one. For a comparison, a payments API such as Stripe would not be a candidate for this approach.
