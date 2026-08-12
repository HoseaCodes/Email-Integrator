package com.hoseacodes.emailintegrator.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Signing configuration for approval-link tokens.
 *
 * <p>Previously this lived in two {@code @Value} annotations whose defaults were
 * {@code default-secret-key-change-in-production} and {@code mySecretKey} — both published in
 * this repository, and neither long enough for HS256. Worse, {@code eb-deploy.sh} never set
 * {@code JWT_SECRET} at all, so the deployed service was signing with a value anyone reading the
 * repo could reproduce and use to forge an approval for any address. See ENGINEERING_AUDIT
 * CRIT-6.
 *
 * <p>There is now no default. A missing or short key fails the application at startup, which is
 * strictly better than the alternatives: starting with a known-bad key is a silent
 * vulnerability, and throwing on first use turns a configuration error into a runtime 500
 * discovered by a user.
 *
 * @param secret     HMAC signing key. At least 32 bytes, because HS256 requires a 256-bit key —
 *                   below that JJWT throws {@code WeakKeyException} at first use.
 * @param expiration how long an approval link stays valid
 * @param issuer     value placed in, and required from, the {@code iss} claim
 */
@Validated
@ConfigurationProperties(prefix = "app.jwt")
public record JwtProperties(

        @NotBlank(message = "app.jwt.secret must be set (env JWT_SECRET); it has no default")
        @Size(min = 32, message = "app.jwt.secret must be at least 32 characters — HS256 requires "
                + "a 256-bit key. Generate one with `openssl rand -base64 32`")
        String secret,

        @NotNull
        Duration expiration,

        @NotBlank
        String issuer) {

    public JwtProperties {
        expiration = expiration == null ? Duration.ofHours(24) : expiration;
        issuer = (issuer == null || issuer.isBlank()) ? "email-integrator" : issuer.trim();
    }
}
