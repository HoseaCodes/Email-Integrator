package com.hoseacodes.emailintegrator.security;

import jakarta.validation.constraints.NotEmpty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.Map;

/**
 * Credentials accepted by this API.
 *
 * <h2>Why API keys rather than JWT bearer tokens</h2>
 * This is a machine-to-machine service. There are no interactive users, no user store, and no
 * identity provider. A JWT bearer flow needs an issuer to mint and rotate tokens; without one,
 * "JWT authentication" would mean this service signing tokens for itself and then verifying
 * them — ceremony that looks sophisticated and secures nothing extra. A validated shared secret
 * per client is the honest fit for the actual requirement.
 *
 * <p>Note this is a different concern from the signed approval links in
 * {@code ApprovalTokenService}. Those are JWTs, and they legitimately are: a one-time,
 * expiring, tamper-evident capability handed to a human in an email. Distinguishing the two is
 * the difference between authenticating a <em>caller</em> and authorising a single
 * <em>action</em>.
 *
 * <h2>Why keys are per-client</h2>
 * Mapping client name to key rather than holding a flat list means logs, metrics, and future
 * rate limits can attribute a request to <em>who</em> sent it, and a single client's key can be
 * revoked without disrupting the others.
 *
 * <p>There is no default key, and startup fails without one. A default would mean a deployment
 * that forgot to configure credentials would come up accepting a publicly known secret —
 * which is how this repository's JWT signing key ended up in the state described in
 * ENGINEERING_AUDIT CRIT-6.
 *
 * <h2>Known limitation</h2>
 * Keys are compared against plaintext values held in configuration. For this project's scale
 * that is a reasonable, explicit trade-off; a production system would store a salted hash and
 * compare against that, so a configuration leak does not immediately yield usable credentials.
 * Recorded in {@code docs/SECURITY.md} rather than left for a reviewer to discover.
 *
 * @param apiKeys    client name to shared secret, from {@code app.security.api-keys.*}
 * @param headerName request header carrying the key; defaults to {@code X-API-Key}
 */
@Validated
@ConfigurationProperties(prefix = "app.security")
public record ApiKeyProperties(

        @NotEmpty(message = "app.security.api-keys must define at least one client key; there is no default")
        Map<String, String> apiKeys,

        String headerName) {

    /** Below this length a key is guessable enough that rate limiting alone would not save it. */
    private static final int MINIMUM_KEY_LENGTH = 32;

    public ApiKeyProperties {
        headerName = (headerName == null || headerName.isBlank()) ? "X-API-Key" : headerName.trim();

        if (apiKeys != null) {
            apiKeys.forEach((client, key) -> {
                if (key == null || key.isBlank()) {
                    throw new IllegalArgumentException(
                            "app.security.api-keys." + client + " is not set. Generate a key with "
                                    + "`openssl rand -base64 32` and supply it via the environment; "
                                    + "see .env.example");
                }
                if (key.length() < MINIMUM_KEY_LENGTH) {
                    // Deliberately does not echo the key itself into the startup log.
                    throw new IllegalArgumentException(
                            "app.security.api-keys." + client + " must be at least "
                                    + MINIMUM_KEY_LENGTH + " characters; generate one with "
                                    + "`openssl rand -base64 32`");
                }
            });
            apiKeys = Map.copyOf(apiKeys);
        }
    }
}
