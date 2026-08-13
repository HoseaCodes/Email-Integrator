# Interview Guide

This file is for me, not for reviewers. It is preparation for defending the decisions in this
repository without reaching for "that's just how I did it."

**How to use it.** Read the short answer, then make sure the deeper explanation is something you
could reconstruct rather than recite. If you cannot explain *why the alternative is worse*, you do
not own the decision yet.

**The one rule:** never claim something the code does not do. Every section below marks what is
implemented and what is analysis. Being caught overstating costs more than the feature was worth —
and this repository's own README and audit are built around that principle, which is itself worth
saying out loud in an interview.

---

## The two-minute walkthrough

Lead with the problem, not the technology list.

> "It's a Spring Boot service that sends transactional email — through Brevo's API and through
> Gmail SMTP. The interesting part isn't calling the API; it's what happens when the provider is
> slow, or returns 429, or accepts the request and then the connection drops.
>
> The core design idea is that sending email isn't idempotent, so a retry isn't free — it's a
> second email to a real person. Every failure is classified by whether the send *might already
> have happened*: a refused connection definitely didn't send, a read timeout might have. That
> distinction drives the API contract — callers get a `deliveryUncertain` flag — and it's why the
> service deliberately doesn't retry.
>
> It's a portfolio project and the README says so. There's a full audit in the repo, including the
> findings that were true of my own earlier code."

Then stop and let them pick a thread. If they don't, offer the HttpClient retry story — it's the
strongest thing in the project.

---

## Architecture

### "Walk me through the system."

**Short answer.** Request hits the Spring Security filter chain, which is deny-by-default and
checks an API key. The controller validates a typed DTO and translates it to a domain command. The
service applies the sending identity from configuration and calls an `EmailProvider` interface. A
provider adapter maps to the vendor's wire format, calls it with explicit timeouts, and translates
failures back into application terms. A single `@RestControllerAdvice` turns those into HTTP
responses.

**Deeper.** The layering is conventional; what's worth defending is the *boundary*. The
`EmailProvider` interface exists so nothing above it imports a vendor type or knows what an HTTP
status code is. Before, `EMSBatchResponse extends EmailResponse` — Brevo's JSON shape was literally
part of this service's published API, so changing providers or even API versions would have broken
callers.

**Files.** [`EmailProvider.java`](../src/main/java/com/hoseacodes/emailintegrator/email/EmailProvider.java) ·
[`BrevoEmailProvider.java`](../src/main/java/com/hoseacodes/emailintegrator/brevo/BrevoEmailProvider.java) ·
[`EmailController.java`](../src/main/java/com/hoseacodes/emailintegrator/controller/EmailController.java)

**Follow-ups.** *"Why an interface with one implementation?"* — see next question. *"Where does
business logic live?"* — honestly, there is very little; this service is an integration boundary,
and pretending otherwise would be dishonest.

---

### "Why an interface with only one implementation? Isn't that premature abstraction?"

**Short answer.** It's not there in case a second provider appears — it's there because it's the
error-translation boundary. Without it the application layer would catch `sendinblue.ApiException`
and vendor types would leak upward.

**Deeper.** This is the distinction worth being crisp on:

- **Useful abstraction** hides something that would otherwise couple you to a detail you don't
  control. Here that's the vendor's exception types and wire format.
- **Premature abstraction** adds indirection for a requirement that doesn't exist. That would be a
  provider registry, a strategy selector, a factory, or a second fake implementation.

I deliberately built the first and not the second. There is one interface, one implementation, no
factory. Adding a real second provider later means writing one class and choosing between beans.

**Follow-up.** *"How would you know if you'd got it wrong?"* — if the interface had to change shape
every time a provider detail changed, it wasn't a boundary, just a pass-through.

---

### "Why not microservices?"

**Short answer.** One deployable, one team, one database's worth of state (none). Microservices
buy independent deploy and scale at the cost of network calls, distributed failure, and
operational overhead. There's nothing here to split.

**Deeper.** The honest version: this service is *already* the kind of thing that would be a
microservice in a larger system — a narrow integration boundary other services call. Splitting it
further would mean a network hop between the controller and the provider adapter, which adds
failure modes and buys nothing.

---

## Integration engineering

### "What happens when the provider times out?"

**Short answer.** The read timeout fires at 10 seconds, the adapter classifies it as
`TIMEOUT` — which is marked side-effect-possible — and the caller gets a 504 with
`deliveryUncertain: true`. We do not retry.

**Deeper.** A read timeout means the request was written and the response never arrived. The
provider very likely processed it. So there are two failures happening at once: the caller doesn't
get an answer, *and* we don't know the state of the world. The second one is the harder problem,
and the design's response is to be explicit about the uncertainty rather than paper over it.

**Files.** [`EmailProviderException.java`](../src/main/java/com/hoseacodes/emailintegrator/email/EmailProviderException.java) ·
[`BrevoEmailProvider.java`](../src/main/java/com/hoseacodes/emailintegrator/brevo/BrevoEmailProvider.java) (`isConnectFailure`)

**Follow-up.** *"How do you distinguish a connect timeout from a read timeout?"* — by walking the
cause chain. `ConnectException` and `UnknownHostException` mean the connection was never
established; Apache HttpClient's connect timeout is a `SocketTimeoutException` subclass matched by
name. Anything unrecognised defaults to "might have sent", because being wrong in that direction
costs a missing email rather than a duplicate one.

---

### "Which errors should be retried? Why not just retry every 500?"

**Short answer.** For a non-idempotent operation, almost none. Only a failure where the request
provably never reached the provider is safe — a refused connection, a DNS failure, a connect
timeout. A 500 might have been raised *after* the message was queued, so retrying it can duplicate
a real email.

**Deeper.** The framing that makes this click: the question isn't "was this a transient failure?"
but "did the side effect happen?" Those are different questions and only the second one matters for
a send.

| Failure | Transient? | Safe to retry? |
|---|---|---|
| Connection refused | Yes | **Yes** — never reached the provider |
| 429 rate limited | Yes | Yes, but not in the request thread |
| 500 | Yes | **No** — may have queued first |
| Read timeout | Yes | **No** — outcome unknown |
| 400 rejected | No | No — will be rejected again |

Every row in the "transient" column says yes. That column is not the one that decides.

**Follow-up.** *"So how would a caller ever retry safely?"* — they'd need an idempotency key. See
the idempotency section; this is the honest answer that leads naturally into it.

---

### "What is exponential backoff? Why jitter? What's a retry storm?"

**Short answer.** Backoff means waiting progressively longer between attempts — 1s, 2s, 4s — so a
struggling dependency isn't hammered. Jitter randomises those delays so clients that failed
together don't retry together. A retry storm is what happens without them: a provider returning
503 under load, retried three times by every caller, receives four times the traffic at its worst
moment.

**Deeper.** The mechanism behind jitter is worth being able to state: if a dependency goes down for
30 seconds, every client fails at roughly the same instant, so every client's backoff schedule
starts at the same instant. Without randomisation they synchronise and arrive as a thundering herd
at each interval — exactly when the dependency is trying to recover. Jitter decorrelates them.

The broader point: **retries convert a partial degradation into an outage.** That's why "just retry
it" isn't a free improvement.

**Files.** [`RELIABILITY.md`](RELIABILITY.md) · [ADR 0002](adr/0002-no-automatic-retries-on-email-send.md)

---

### ⭐ "Tell me about a bug you found in this project."

This is the strongest story in the repository. Have it ready even if they don't ask.

**Short answer.** I wrote a test asserting that one send produces exactly one HTTP request. It hung
the test suite for ten minutes. A thread dump showed Apache HttpClient sleeping inside
`HttpRequestRetryExec` — it retries automatically by default, and on 429 and 503 it honours the
provider's `Retry-After` before re-sending. My stub said `Retry-After: 42`, so it slept 42 seconds
and sent the email again.

**Deeper — this is where the value is.** Three things made it dangerous:

1. **Nothing in my code mentioned retries.** The behaviour was a library default.
2. **The library arrived transitively.** Spring's `ClientHttpRequestFactories.get()` picks an HTTP
   client by classpath scan, and `httpclient5` was on the classpath via **Spring Cloud Vault** — a
   dependency completely unrelated to email. So the retry policy for non-idempotent sends was being
   set by an unrelated dependency's dependency tree.
3. **It only surfaced because the test asserted a negative.** A happy-path test would have passed.

The fix was three parts: declare `httpclient5` directly so the client is a decision rather than an
inheritance, call `.disableAutomaticRetries()` explicitly, and add tests asserting exactly one
request for 429, 500, and 503 plus one asserting a 429 fails fast rather than sleeping.

**The lesson to state out loud:** defaults you didn't choose are still your behaviour, and for
side-effecting operations you have to go looking for them.

**Files.** [`BrevoClientConfig.java`](../src/main/java/com/hoseacodes/emailintegrator/brevo/BrevoClientConfig.java) ·
[`BrevoEmailProviderTest.java`](../src/test/java/com/hoseacodes/emailintegrator/brevo/BrevoEmailProviderTest.java) (`NoRetries`)

**Follow-up.** *"How would you catch this class of bug generally?"* — assert negatives, not just
positives. "Exactly one request" is the kind of assertion that catches invisible behaviour.

---

## Idempotency

### "What happens if the same email request is submitted twice?"

**Short answer.** Two emails are sent. There's no idempotency mechanism — that's a documented gap,
not an oversight, and `RELIABILITY.md` explains what it would take to close it.

**Deeper.** Don't pretend otherwise, and don't apologise either — explain the window precisely,
because that's what shows you understand it:

```
Client ──POST──▶ Service ──▶ Provider   ✅ accepted, queued
                                 │
                   ✗ response lost / read timeout
                                 │
Client ◀── 504 ──────────────────┘

The client cannot distinguish "not sent" from "sent, response lost".
```

What the service *does* do is tell the caller which case they're in as far as it can:
`deliveryUncertain: true` means a blind retry risks a duplicate. That's honest signalling in place
of a guarantee.

**Follow-up.** *"Why didn't you implement it?"* — I could have, in memory, in an afternoon. It
would be correct on one instance and silently wrong on two. In a portfolio that's worse than
nothing, because it *looks* solved. Naming the problem precisely is more useful than a
half-implementation.

---

### "How would you implement idempotency in a distributed environment?"

**Short answer.** A client-supplied `Idempotency-Key` header, and a shared store mapping key →
outcome with an atomic insert-or-fail so two concurrent requests with the same key can't both
proceed.

**Deeper.** In increasing order of robustness:

1. **In-memory cache** — fine for one instance, wrong the moment there are two.
2. **Shared store** (Redis, or a table with a unique constraint on the key). The interesting part
   is the race: it must be an atomic insert-or-fail, not check-then-act. Two requests arriving
   simultaneously with the same key must have exactly one win.
3. **Provider-side deduplication** — strictly better where supported, because it survives our
   process dying mid-send.
4. **Transactional outbox** — persist the intent, return 202, let a worker own delivery with full
   knowledge of what's been attempted. The robust answer, and the biggest change: it turns a
   synchronous API asynchronous.

**Follow-up.** *"What do you store — just the key?"* — key, the outcome, and enough of the request
to detect a key reused with a *different* payload, which is a client bug worth rejecting rather
than silently replaying.

---

## Security

### "How does authentication work here, and why API keys rather than JWTs?"

**Short answer.** A per-client API key in an `X-API-Key` header, compared in constant time, behind
a deny-by-default filter chain. Not JWT bearer tokens, because there's no user store and no
identity provider — a bearer flow would mean this service minting tokens for itself and then
verifying them, which is ceremony that secures nothing extra.

**Deeper.** The distinction to draw is **authentication** (who is calling) versus **authorization**
(what they may do). API keys answer the first. There's currently one role, so the second is
trivial — and I'd rather say that than invent a permission model.

There *are* JWTs in this project, but for something different: the approval links emailed to an
administrator. That's a **capability** — a single, expiring, tamper-evident permission to perform
one action, handed to a human whose mail client cannot attach headers. Being able to articulate why
those are two different mechanisms is the point.

**Files.** [`SecurityConfig.java`](../src/main/java/com/hoseacodes/emailintegrator/security/SecurityConfig.java) ·
[`ApiKeyAuthenticationFilter.java`](../src/main/java/com/hoseacodes/emailintegrator/security/ApiKeyAuthenticationFilter.java)

**Follow-ups.** *"Why constant-time comparison?"* — `String.equals` returns on the first differing
character, so response time leaks how many leading characters were right; over many requests that
reconstructs a key. `MessageDigest.isEqual` is time-constant, and the lookup loop deliberately
doesn't `break` on a match so timing doesn't depend on the key's position either.

*"What's the weakness?"* — keys are compared against plaintext values in configuration. Production
would store a salted hash. It's documented rather than hidden.

---

### "Why is CSRF protection disabled? Isn't that a vulnerability?"

**Short answer.** No — CSRF exploits *ambient* credentials, cookies the browser attaches
automatically. This API is stateless, issues no cookies, and authenticates via a header a browser
will never add on its own. A cross-site form post arrives unauthenticated and is rejected.

**Deeper.** The trap is disabling CSRF because it's inconvenient and then adding cookie sessions
later. The condition that makes it safe is "no ambient credentials" — if that ever changes, this
must be revisited. That's why the reasoning is a comment in the config rather than folklore.

---

### "Walk me through a security problem you found in your own code."

**Short answer.** `POST /auth/send-email` took no authentication, accepted an arbitrary recipient,
and accepted a `resetUrl` that was injected unescaped into a password-reset email sent from a real
domain. So anyone could make my domain deliver a password-reset email pointing at their link. It
was live on the public internet.

**Deeper.** Fixed in layers, deliberately, rather than with one patch:

1. **Authentication** closed the anonymous path — the biggest single win.
2. **Context-aware escaping.** Text is HTML-escaped; URLs go through scheme and host validation
   *first*, because escaping alone leaves `javascript:alert(1)` a perfectly valid `href`. HTML is
   not one context.
3. **Link allowlisting** so even an authenticated-but-misbehaving client can't aim a reset link at
   a host it controls.

Writing the tests found two more injection paths I hadn't audited: sequential placeholder
replacement re-scanned its own output, so a value containing `{{resetUrl}}` got expanded on a later
pass; and `Matcher.appendReplacement` treats `$1` as a group reference, so a `$` in any value could
corrupt the output.

**Files.** [`EmailTemplateService.java`](../src/main/java/com/hoseacodes/emailintegrator/service/EmailTemplateService.java) ·
[`LinkSanitizer.java`](../src/main/java/com/hoseacodes/emailintegrator/service/LinkSanitizer.java)

**Follow-up.** *"How did you find it?"* — I audited my own repository before touching it and wrote
the findings down. That document is still in the repo with a status table.

---

### "Where's the JWT signing secret stored? How would you rotate it?"

**Short answer.** An environment variable, injected as an Elastic Beanstalk environment property by
the deploy script. It has no default and must be at least 32 bytes — the application refuses to
start otherwise.

**Deeper.** This is worth telling as a failure story. The secret previously defaulted to
`default-secret-key-change-in-production`, a string published in this repository, *and* the deploy
script never set `JWT_SECRET` at all — it only set `SERVER_PORT`. So production was almost certainly
signing approval tokens with a key anyone reading the repo could reproduce and use to forge an
approval for any address.

The fix wasn't just removing the default; it was making the failure loud. Fail-fast at startup
beats failing at first use, because a misconfiguration shows up in a deployment log instead of a
support ticket days later.

**Rotation** with a symmetric key and short-lived tokens: accept both old and new keys for one token
lifetime, then drop the old. This service doesn't implement that — with a 24-hour token lifetime,
rotating invalidates outstanding approval links, which is acceptable here and worth saying plainly.

**Files.** [`JwtProperties.java`](../src/main/java/com/hoseacodes/emailintegrator/config/JwtProperties.java) ·
[`ApprovalTokenService.java`](../src/main/java/com/hoseacodes/emailintegrator/service/ApprovalTokenService.java)

---

### "How would JWT revocation work? What are the trade-offs of stateless auth?"

**Short answer.** It can't, as built — that's the trade. A stateless token is valid until it
expires because nothing is consulted at verification time. The 24-hour lifetime *is* the
containment window.

**Deeper.** Stateless authentication buys you no lookup on the hot path and no shared session store.
It costs you revocation. The usual resolutions:

- **Short lifetimes** — shrink the window rather than closing it.
- **A denylist of `jti` claims** — reintroduces the state you were avoiding, but only for revoked
  tokens, which is a much smaller set than all sessions.
- **Access + refresh tokens** — short-lived access tokens, revocation enforced at refresh.

I deliberately didn't implement refresh tokens: there's no interactive user session to keep alive,
so they'd be machinery without a purpose. That restraint is the answer, not a gap.

**Follow-up.** *"What happens when a token expires?"* — verification throws `ExpiredJwtException`,
it's logged at INFO as routine, and the caller gets a generic "invalid or expired" 400. Deliberately
generic: telling the presenter *which* it was helps someone probing the endpoint.

---

## Spring Boot

### "How does Spring Security intercept a request?"

**Short answer.** A servlet `Filter` — `springSecurityFilterChain` — sits in front of the
`DispatcherServlet`, so security runs before any controller code. My API key filter extends
`OncePerRequestFilter` and populates the `SecurityContextHolder`; the authorization rules then
decide, and rejection is handled by an `AuthenticationEntryPoint`.

**Deeper.** The detail worth knowing: the filter never writes a response itself. It authenticates or
does nothing, and the "who's allowed where" decision lives entirely in `SecurityConfig`. Splitting
that across a filter and a config class is how rules end up contradicting each other.

**Follow-up.** *"Why deny-by-default?"* — `anyRequest().authenticated()` is the last rule, so a new
endpoint is protected the moment it's written. Permit-by-default fails open, and the endpoint
someone forgets to list is exactly the one that matters. I verified this isn't just intent by
mutation-testing it: flipping it to `permitAll()` fails 9 of 18 security assertions.

---

### "Why DTOs instead of exposing your domain objects?"

**Short answer.** The DTO is the published wire contract; the domain type is free to change. If
they're the same class, every internal refactor is a breaking API change.

**Deeper.** This project has a concrete example of the failure. `EmailInput` exposed
`EMSBatchInput` — Brevo's request shape — and `EMSBatchResponse extends EmailResponse`. So the
vendor's JSON *was* the public contract. Swapping providers, or Brevo changing their API, would
have broken every caller.

There's a second, subtler benefit: **the DTO can omit fields on purpose.** `EmailDraft` has no
`sender` field, so caller-controlled sender spoofing can't be expressed at all — the invariant is
in the type rather than in a check somebody might delete.

**Files.** [`SendEmailRequest.java`](../src/main/java/com/hoseacodes/emailintegrator/controller/dto/SendEmailRequest.java) ·
[`EmailDraft.java`](../src/main/java/com/hoseacodes/emailintegrator/service/EmailDraft.java)

---

### "How does centralized exception handling work?"

**Short answer.** `@RestControllerAdvice` extending `ResponseEntityExceptionHandler`. Every failure
becomes one `ApiError` shape with a correlation id, and no controller has a `try/catch`.

**Deeper.** Two details worth raising because they're where people get it wrong:

- **Extending the base class matters.** My first version only had a catch-all
  `@ExceptionHandler(Exception.class)`, which swallowed Spring's own MVC exceptions and reported
  415s and 405s as 500 — telling callers the server was broken when their request was. A test
  caught it. Extending `ResponseEntityExceptionHandler` keeps the framework's status mapping and
  overrides only the body.
- **Status codes describe who must act.** A provider auth failure is a 502, never a 401 — the
  caller's credentials were fine, *ours* are wrong. Returning 401 would send them to re-authenticate
  pointlessly.

**Files.** [`ApiExceptionHandler.java`](../src/main/java/com/hoseacodes/emailintegrator/controller/ApiExceptionHandler.java)

---

### "What is dependency injection actually buying you here?"

**Short answer.** Testability, mostly. `BrevoEmailProvider` takes a `RestClient` in its constructor,
so tests point it at a local WireMock server with no framework and no static state.

**Deeper.** The counter-example is in this repo's history. The Brevo SDK authenticated through
`Configuration.getDefaultApiClient()` — a process-wide static. That's untestable in isolation and
not thread-safe under concurrency. Constructor injection isn't ceremony; it's what makes the
provider failure tests possible at all.

---

## Testing

### "What do your tests actually prove?"

**Short answer.** Mostly that failures are handled correctly, because that's the part that isn't
exercised by hand. 161 tests: provider 400/401/403/429/5xx, read timeout, connection refused,
truncated body; JWT tampering, expiry, wrong issuer, `alg: none`; template injection; the HTTP
error contract.

**Deeper.** The deliberate weighting: the happy path gets exercised constantly during development.
The 429-at-3am path gets exercised exactly once, in production, unless it's tested. So the failure
paths get more coverage than the success path, on purpose.

**Follow-up.** *"Why WireMock instead of mocking the HTTP client?"* — mocking the client would only
assert that my code calls methods I already know it calls. The behaviour worth proving lives below
that line: how a 429 is classified, whether a read timeout is distinguished from a refused
connection. Those need a real socket, real status codes, real delays.

---

### "What shouldn't be mocked?"

**Short answer.** The thing under test, and anything whose real behaviour is the point. I mock the
provider's *server*, not my own HTTP client — because timeout behaviour is what I'm testing.

**Deeper.** A concrete example: in the SMTP tests I mock `JavaMailSender` but let it return a *real*
`MimeMessage`, so assertions check the message actually composed — headers, recipients, attachments
— rather than merely that a method was called. Mocking the `MimeMessage` too would have made the
tests pass while proving nothing.

That said, mocking the sender did hide something: `Message-ID` was null because the mock never
called `saveChanges()`. Chasing that led to a better design — assigning the ID *before* sending, so
a timeout still leaves something to search mail logs for.

---

### "How do you know your security tests actually test anything?"

**Short answer.** I mutation-tested them. I changed `anyRequest().authenticated()` to `permitAll()`
and re-ran: 9 of 18 assertions failed, and `manual-approve` returned 200 — confirming that endpoint
really would execute for an anonymous caller. Then I restored the config and diffed it.

**Deeper.** This matters because `EmailControllerTest` runs with `addFilters = false` so it can test
controller behaviour without every case needing a credential. Disabling security in tests is only
legitimate if the rules are genuinely asserted somewhere else — otherwise it's exactly the
anti-pattern it looks like. The mutation is the evidence that "somewhere else" is real.

**Files.** [`ApiKeySecurityTest.java`](../src/test/java/com/hoseacodes/emailintegrator/security/ApiKeySecurityTest.java)

---

## AWS

### "Why Elastic Beanstalk? When would you use ECS/Fargate?"

**Short answer.** Beanstalk is adequate for one containerised service and hands you deploys,
health checks, and log shipping without building them. Fargate becomes worth it with several
services sharing infrastructure, when you need per-task IAM roles, or finer scaling control.

**Deeper.** The answer that lands is the restraint: migrating a single service to ECS to sound more
sophisticated would be a worse decision than staying and being able to explain why. The trigger for
moving is a *requirement*, not a preference.

**Follow-up.** *"What's wrong with your current deployment?"* — be direct: it's single-instance, so
any deploy is downtime; and it has **no TLS**, because a single-instance environment has no load
balancer and therefore no certificate termination point. That's why the README says not to put real
traffic through it.

---

### "How would you add TLS?"

**Short answer.** CloudFront in front of the instance with a free ACM certificate — roughly $0 at
this traffic, versus ~$16–18/month for an ALB.

**Deeper.** State the caveat before they find it: the CloudFront→origin hop stays plain HTTP unless
the origin also gets a certificate. That traffic is inside AWS's network, which is defensible, but
it is **not end-to-end TLS** and I don't describe it as such. Restricting the origin security group
to CloudFront's prefix list narrows it further.

I rejected Let's Encrypt on the instance because renewal breaks on instance replacement — a
portfolio shouldn't showcase something that fails silently.

---

### "How should secrets reach the application?"

**Short answer.** Currently EB environment properties, set by the deploy script from the deploying
shell's environment — never written to disk, never on a command line where `ps` could read them.
Production should use SSM Parameter Store `SecureString` or Secrets Manager with an IAM-scoped
instance role.

**Deeper.** Name the limitation without being asked: EB environment properties are readable by
anyone with console access to the environment and appear in `describe-configuration-settings`. They
aren't encrypted under a key you control and there's no rotation or audit trail. Parameter Store is
free at this scale and adds all three.

**Follow-up.** *"What about Vault?"* — the repo has a Vault dependency that is completely inert:
`spring.cloud.vault.enabled=false`, and the config class binds `example.username`/`example.password`
from the getting-started guide. I flagged it in my own audit rather than letting the dependency
imply enterprise secret management. A dependency plus a tutorial config class is *weaker* evidence
than no Vault at all.

---

## Observability

### "What would you monitor? What's missing?"

**Short answer.** Implemented: health, and structured logging that records operation, outcome,
duration, and recipient counts — with an `errorId` in every error response matching a log line. Not
implemented: metrics, request-scoped correlation IDs on success, and tracing. That's on the roadmap.

**Deeper.** The small set worth adding, and no more: a counter for send attempts tagged by provider
and outcome, a timer for provider latency, a counter for authentication failures. Those answer "is
it working", "is it slow", and "is someone probing us". Dozens of vanity gauges answer nothing.

**Follow-up.** *"Health vs readiness vs liveness?"* — liveness: is the process alive, restart if
not. Readiness: can it serve traffic *right now*, remove from the load balancer if not. Health is
often the aggregate. This service exposes only aggregate health, which is honest for a
single-instance deployment where there's no load balancer to act on readiness.

*"How would you debug elevated provider latency?"* — the integration log line already records
duration per call, so the first question is whether latency rose at the provider or in our queueing.
A timer with percentiles would answer it properly; today it's log analysis.

---

### "What don't you log, and why?"

**Short answer.** API keys, JWTs, provider credentials, message bodies, and recipient addresses.
Recipient *counts* are logged instead.

**Deeper.** This is another self-audit finding: the old Brevo delegate printed the API key to stdout
on every send via `System.out.println`. On Elastic Beanstalk that goes to instance logs and
CloudWatch — so the credential was in every downstream system that touched logs, and log retention
outlives credential rotation. Log access is also granted far more broadly than secret access, which
is exactly why it's such an effective leak.

The rule now: credentials never, at any level. Provider error *codes* are logged; provider error
*messages* go to DEBUG only, because they can echo recipient addresses and account identifiers.

---

## Reliability

### "What would break first at 10× traffic?"

**Short answer.** CPU credits on the `t3.micro` — it's burstable, so sustained load throttles it.
Then the Tomcat worker pool if the provider slows. Then Gmail's daily send limit, which is a hard
quota.

**Deeper.** Be explicit that this is reasoning, not measurement: nothing has been load tested, and
no throughput or latency figure appears anywhere in the repository. Saying that unprompted is
better than being caught with an invented number.

---

### "How would a circuit breaker help? When does it make things worse?"

**Short answer.** It stops calling a failing dependency so requests fail instantly instead of each
burning a 10-second timeout — protecting the worker pool. It makes things worse when it opens on a
transient blip and rejects requests that would have succeeded, or when every instance's half-open
probe hits a recovering provider simultaneously and knocks it over again.

**Deeper.** Why it's not in this project: on a single instance, timeouts already bound the damage. A
breaker earns its keep at a concurrency level where many workers pile onto a failing dependency at
once. Adding Resilience4j now would lengthen the technology list without addressing a problem the
service has — and I'd rather defend the omission than the addition.

---

## AI-assisted engineering

Expect this, and expect it to be the differentiator. The bad answer is either "I didn't use AI" or
"AI wrote it and it works."

### "How did you use AI on this repository?"

**Short answer.** Heavily, and as a collaborator rather than an author. It audited the existing code
against my instructions, proposed changes, and wrote a lot of the implementation and tests. I set
the constraints, made the architectural calls, and treated the tests and a running application as
the check on everything generated.

**Deeper.** The concrete guardrails:

- **An audit first, before any changes.** Findings written down with severities and file references,
  then a status table tracking what got fixed. That document is still in the repo.
- **A hard accuracy rule:** no claim in documentation that isn't backed by code. That rule caught
  fabricated claims in the *original* README — "Thymeleaf" was never a dependency, "JWT
  Authentication: Implemented and secure" when there was no authentication at all.
- **Verification over assertion.** Fail-fast startup was tested by running the packaged jar with the
  variable unset. The Docker changes were tested by building and running the image. The CI workflow
  was tested against a clean clone with an empty environment.

**Files.** [`ENGINEERING_AUDIT.md`](ENGINEERING_AUDIT.md) · [`AGENTS.md`](../AGENTS.md)

---

### "How did you stop it weakening security to make tests pass?"

**Short answer.** That exact temptation came up. `EmailControllerTest` needed `addFilters = false`
to test controller behaviour without every case carrying a credential — which is the classic way
authentication quietly stops working. So the security rules are asserted separately against the
real filter chain, and I mutation-tested that suite to prove it wasn't vacuous.

**Deeper.** The general principle: AI optimises for the goal you state. "Make the tests pass" and
"make the code correct" diverge exactly where it matters most. The defence is to state the
constraint as part of the goal, and to verify the check itself still checks.

---

### "Which decisions were yours and which were the AI's?"

**Short answer.** The scope and the constraints were mine. Most implementation was AI-written under
those constraints. The architectural calls I own and can defend — the interface as an
error-translation boundary rather than provider anticipation, API keys over JWT bearer tokens,
no retries, no circuit breaker, and not shipping a single-instance idempotency cache that would be
silently wrong on two.

**Deeper — the answer that separates people.** The value wasn't generated code, it was
*throughput on the boring parts* — writing 161 tests, keeping documentation consistent with code —
which freed attention for the judgment calls. And AI didn't find the HttpClient retry bug: a test
did. AI wrote that test, but only because I'd asked for failure-mode coverage.

**Follow-up.** *"What shouldn't be delegated?"* — anything where being confidently wrong is
expensive and hard to detect: security boundaries, retry semantics on side-effecting operations,
and any claim about what the system does. Those need verification, not review-by-reading. Several
generated assertions in this repo were plausible and wrong — checking `getContent().toString()` on
a `MimeMessage`, or asserting an escaped payload wouldn't contain a substring that escaping doesn't
remove. They passed reading and failed running.

---

## Questions where the answer is "not implemented"

Have these ready. Answering cleanly is a strength; hedging is not.

| Question | Answer |
|---|---|
| "Is this production-ready?" | "No, and the README says so. No TLS, no idempotency, no rate limiting. It demonstrates production-minded practices, not a production system." |
| "What's your test coverage?" | "I haven't measured a percentage and don't quote one. 161 tests weighted toward failure paths. Coverage percentage is easy to game and I'd rather talk about what's actually covered." |
| "How many emails does it send?" | "It's a portfolio project. There are no production users and no volume figures anywhere in the repo, deliberately." |
| "Is it secure?" | "It's had an audit I wrote up, and the findings are in the repo with a status table. Known remaining gaps are no TLS in the deployment and API keys compared against plaintext config. Nothing has been penetration tested." |
| "What would you do next?" | "TLS, then idempotency keys — they're the two that change what it can honestly claim. There's a roadmap in the repo with the reasoning." |

---

## Final check

Before sending the repository, confirm each of these is still true:

- [ ] Gmail app password rotated and `production-secrets-backup.txt` deleted
- [ ] `mvn clean verify` passes from a clean clone
- [ ] CI badge green
- [ ] No claim in the README lacks code behind it
- [ ] You can explain every item in [ROADMAP.md](ROADMAP.md) without reading it
