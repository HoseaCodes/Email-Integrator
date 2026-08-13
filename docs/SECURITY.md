# Security

The security model, what it deliberately does not do, and the gaps that remain. Referenced from
`ApiKeyProperties` and `SecurityConfig` in the source.

!!! warning "Not a production security posture"
    This is a portfolio project. It has not been penetration tested, the deployment has no TLS, and
    there is no rate limiting. Known gaps are listed in full under
    [Known limitations](#known-limitations) rather than omitted.

---

## Two authentication mechanisms, for two different jobs

The most common confusion this codebase invites is that it "uses JWT for authentication". It does
not. It uses two mechanisms because there are two different problems.

| | API keys | Approval-link JWTs |
|---|---|---|
| **Answers** | Who is calling? | May this one action be performed? |
| **Presented by** | A service, in a header | A human, clicking a link in an email |
| **Lifetime** | Until rotated | 24 hours |
| **Scope** | Every protected endpoint | One approval or denial |
| **Called** | Authentication | Authorisation of a capability |

An email client cannot attach an `X-API-Key` header, which is precisely why the approval links need
a credential they can carry in the URL. That is the whole reason the JWT machinery exists.

---

## Authentication — API keys

**Model.** A per-client shared secret sent as `X-API-Key`. Configured as
`app.security.api-keys.<client-name>`, so requests can be attributed to a client and a single
client's key revoked without disturbing the others.

**Why not JWT bearer tokens.** There is no user store and no identity provider. A bearer flow needs
an issuer to mint and rotate tokens; without one, "JWT authentication" would mean this service
signing tokens for itself and then verifying them — ceremony that looks sophisticated and secures
nothing extra. A validated shared secret is the honest fit for a machine-to-machine service.

**Constant-time comparison.** Keys are compared with `MessageDigest.isEqual`, not `String.equals`.
String comparison returns as soon as it finds a differing character, so response time leaks how
many leading characters were correct — enough, over many requests, to reconstruct a key one
character at a time. The lookup loop also does not `break` on a match, so timing does not depend on
a key's position in the map either.

**No default key.** The application fails to start without one. A default would mean a deployment
that forgot to configure credentials would come up accepting a publicly known secret — which is
exactly how this repository's JWT signing key ended up in the state described in
[the audit](ENGINEERING_AUDIT.md), CRIT-6.

**Minimum length** is 32 characters, enforced at startup.

---

## Authorization

There is currently **one role**, `ROLE_API_CLIENT`. Every authenticated client may call every
protected endpoint.

This is stated plainly rather than dressed up: with a single class of caller, a permission model
would be scaffolding. If a second class of caller appeared — say, a client permitted to send
transactional mail but not to approve registrations — the authority is already attached to the
authentication token and `SecurityConfig` would gain `hasAuthority(...)` rules.

---

## The security filter chain

Deny by default. `anyRequest().authenticated()` is the **last** rule, so a new endpoint is protected
the moment it is written. Permit-by-default fails open, and the endpoint someone forgets to list is
always the one that matters.

Three endpoints are deliberately public, each for a stated reason:

| Endpoint | Why it cannot carry a credential |
|---|---|
| `GET /actuator/health` | The platform health check cannot present one. Returns aggregate status only — component detail stays gated |
| `GET /auth/approve`, `GET /auth/deny` | Clicked from an email client, which cannot attach headers. **Not unauthenticated** — the signed JWT in the query string is the credential |
| OpenAPI schema and Swagger UI | Describes the contract, exposes no data. Worth restricting in a real production deployment |

**Verified, not assumed.** The security tests were checked by mutation: changing
`anyRequest().authenticated()` to `permitAll()` fails 9 of 18 assertions, and `manual-approve`
returns 200 — confirming that endpoint really would execute for an anonymous caller. A security
test that passes against broken configuration is worse than no test.

### Why CSRF protection is disabled

Not for convenience. CSRF exploits **ambient** credentials — cookies a browser attaches
automatically. This API is stateless, issues no cookies, and authenticates via a header a browser
will never add on its own, so a cross-site form post arrives unauthenticated and is rejected.

The condition that makes this safe is "no ambient credentials". **If cookie-based sessions are ever
introduced, this must be revisited.**

---

## JWT design (approval links)

| Property | Value |
|---|---|
| Algorithm | HS256 (HMAC-SHA256) |
| Key | `app.jwt.secret`, no default, minimum 32 bytes |
| Lifetime | 24 hours (`app.jwt.expiration`) |
| Claims | `email`, `name`, `type`, `sub`, `iss`, `iat`, `exp` |

**What is verified, in order:** signature, then expiry, then issuer, then the custom `type` claim.

The ordering matters. The `type` check happens **after** signature verification, because claims from
an unverified token are attacker-controlled input and must not influence any decision.

**Issuer validation** with a single issuer guards one specific mistake: two environments accidentally
sharing a signing key, where a staging approval link would otherwise be accepted in production.

**No audience claim.** `aud` distinguishes multiple intended recipients of a token, and there is
exactly one consumer — this service. Adding it would be ceremony without a threat behind it.

**Failure handling.** Every rejection returns the same generic "invalid or expired" response.
Whether a token was expired, forged, or of the wrong type is useful mainly to someone probing the
endpoint; the distinction is recorded in the logs instead.

### Revocation

Stateless tokens cannot be withdrawn before they expire. **The 24-hour lifetime is the entire
containment window**, and shortening it is the cheapest lever.

Real revocation needs server-side state — the standard approach is a `jti` claim plus a store of
consumed or revoked ids, checked at verification. That would also make the links single-use, which
they currently are not (see [Known limitations](#known-limitations)). Deliberately not built: a
token store is a design decision that belongs with the idempotency work, not bolted on.

Refresh tokens are **not** implemented, and should not be. There is no interactive user session to
keep alive.

---

## Input validation and output encoding

**Validation.** Every request body is a typed record with Bean Validation. `/auth/send-email` uses a
sealed discriminated type, so each `templateType` declares its own required fields. Size limits are
not arbitrary: 254 characters is the maximum length of an email address (RFC 5321), 998 the maximum
header line length (RFC 5322).

Invalid input is rejected **before** any provider is contacted, so malformed requests cost no quota.

**Output encoding is context-aware**, because HTML is not one context:

- **Text values** are HTML-escaped.
- **URL values** are validated by `LinkSanitizer` *first* — scheme allowlist (`http`/`https` only,
  not configurable) and an optional exact-match host allowlist — and then escaped. Escaping alone
  leaves `javascript:alert(1)` a perfectly valid `href`.

Two subtler injection paths are also closed: template substitution is a single regex pass, so a
value containing `{{resetUrl}}` cannot be expanded on a later pass; and `Matcher.quoteReplacement`
neutralises `$` in values, which would otherwise be treated as a capture-group reference.

!!! tip "Set the host allowlist"
    `app.email.allowed-link-hosts` is optional and empty by default. Empty is safe against anonymous
    abuse now that sending requires authentication, but **not** against a leaked client key. A
    warning is logged at startup when it is unset.

---

## Sender identity

Callers **cannot** choose the `From` address on either send path. `EmailDraft` and the request DTOs
have no `sender` field at all, so spoofing cannot be expressed — the invariant lives in the type
rather than in a check someone might later remove.

Previously `SpringMailService` passed a caller-supplied `from` straight to
`MimeMessageHelper.setFrom`, and accepted an arbitrary `replyTo` — enough for conversation hijacking
even where the provider rewrites `From`.

---

## Secret management

**Everything comes from environment variables.** Nothing is committed;
[`.env.example`](https://github.com/HoseaCodes/Email-Integrator/blob/master/.env.example) documents
every value with placeholders.

| Variable | Purpose |
|---|---|
| `API_KEY_DEFAULT` | Client credential for `X-API-Key` |
| `BREVO_API_KEY` | Brevo authentication |
| `JWT_SECRET` | Approval-link signing key |
| `MAIL_PASSWORD` | Gmail app password |

All four are **required** — the application refuses to start without them, rather than booting into
a state where every request would fail.

**In transit to AWS:** `eb-deploy.sh` validates them before uploading anything and sets them as
Elastic Beanstalk environment properties. They are never written to disk, never placed on a command
line where `ps` could read them, and are passed to the AWS API through a mode-600 temporary file
removed on exit.

**Never logged**, at any level: API keys, JWTs, provider credentials, `Authorization` and
`X-API-Key` header values, message bodies, recipient addresses. Provider error *codes* are logged;
provider error *messages* go to DEBUG only, because they can echo recipient addresses and account
identifiers.

This rule exists because it was broken: the old Brevo integration printed the API key to stdout on
every send, which on Elastic Beanstalk reaches CloudWatch — and log retention outlives credential
rotation, while log access is granted far more broadly than secret access.

### Vault

The repository has a `spring-cloud-starter-vault-config` dependency that is **completely inert**:
`spring.cloud.vault.enabled=false`, and the configuration class binds `example.username` /
`example.password` — the property names from the Spring Cloud Vault getting-started guide.

This is called out rather than left to imply enterprise secret management. A dependency plus a
tutorial config class is *weaker* evidence than no Vault at all, because it shows an integration
that was started and abandoned. Removal is tracked in [the roadmap](ROADMAP.md).

---

## Error responses

One `ApiError` shape across every endpoint, carrying a correlation `errorId` that also appears in
the server log.

Never exposed to callers: stack traces, provider response bodies, SMTP hostnames, ports, account
identifiers, or internal exception messages. An unexpected failure returns a generic message plus
the `errorId` — the caller can quote it, and the detail can be found in the logs, without handing an
attacker free reconnaissance.

Status codes describe **who must act**. A provider authentication failure is a 502, never a 401: the
caller's credentials were fine, ours are wrong, and a 401 would invite them to re-authenticate
pointlessly.

---

## Abuse considerations

An exposed email-sending service is an attractive target. The concerns, and where each stands:

| Risk | Status |
|---|---|
| **Anonymous spam / phishing relay** | **Closed.** Every sending endpoint requires an API key |
| **Injected links in outgoing mail** | **Closed** for scheme; host allowlist available but opt-in |
| **Sender spoofing** | **Closed.** Sender is not a request field |
| **Provider quota exhaustion** | **Open.** Authentication bounds *who*, not *how much* |
| **Credential brute force** | **Partially mitigated.** Constant-time comparison and a 32-character minimum, but nothing throttles attempts |
| **Denial of service** | **Open.** No rate limiting; single instance |

!!! danger "Do not expose this to untrusted clients"
    There is no rate limiting. An authenticated client can send until the provider's quota is
    exhausted. This service is safe to expose only to clients you control.

---

## Known limitations

- **No TLS in the deployment.** Single-instance Elastic Beanstalk has no load balancer and therefore
  no certificate termination point. API keys and approval tokens currently cross the network in
  cleartext. See [AWS architecture](AWS_ARCHITECTURE.md).
- **API keys are compared against plaintext configuration values**, not salted hashes. A
  configuration leak yields immediately usable credentials.
- **No rate limiting.**
- **`GET /auth/approve` and `/auth/deny` change state.** A mail client's link prefetcher can trigger
  an approval nobody clicked. Needs single-use tokens.
- **No token revocation.** The 24-hour lifetime is the containment window.
- **Secrets are readable in the AWS console.** EB environment properties are not encrypted under a
  key you control and have no audit trail. SSM Parameter Store is the production answer.
- **Spring Boot 3.2.5 is past its OSS support window**, and no dependency vulnerability scanner runs
  in CI.
- **Not penetration tested.** No security assessment beyond the self-audit in this repository.

## Reporting

This is a portfolio project with no production users. If you find something, open an issue on
[GitHub](https://github.com/HoseaCodes/Email-Integrator/issues).
