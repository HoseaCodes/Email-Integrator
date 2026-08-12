package com.hoseacodes.emailintegrator.brevo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Brevo integration configuration.
 *
 * <p>Annotated {@code @Validated} so a missing or malformed value fails the application at
 * <em>startup</em> rather than at the first send. That is a deliberate choice: a service whose
 * job is to deliver email should refuse to start if it cannot, instead of accepting traffic and
 * failing per-request. Misconfiguration then shows up in a deployment log, loudly, rather than
 * in a customer's support ticket days later.
 *
 * <p>The API key has no default. The previous implementation defaulted and then ignored the
 * configured value in favour of a literal in source — see {@code docs/ENGINEERING_AUDIT.md}
 * CRIT-3. A key must be supplied via the {@code BREVO_API_KEY} environment variable.
 *
 * <p>Note there is no sender address here. The From identity is an application-level concern
 * shared by every delivery path, so it lives once in {@code app.email.*}
 * ({@code EmailProperties}) rather than being duplicated per provider.
 *
 * @param apiKey         Brevo API key. Never logged, never returned in a response.
 * @param baseUrl        API root; overridable so tests can point at a local WireMock server.
 * @param connectTimeout cap on establishing a TCP connection.
 * @param readTimeout    cap on waiting for a response once the request has been written.
 */
@Validated
@ConfigurationProperties(prefix = "brevo")
public record BrevoProperties(

        @NotBlank(message = "brevo.api-key must be set (env BREVO_API_KEY); it has no default")
        String apiKey,

        @NotBlank
        String baseUrl,

        @NotNull
        Duration connectTimeout,

        @NotNull
        Duration readTimeout) {

    public BrevoProperties {
        // Defaults for everything except the API key, which must be supplied explicitly.
        baseUrl = (baseUrl == null || baseUrl.isBlank()) ? "https://api.brevo.com" : baseUrl;
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(3) : connectTimeout;
        readTimeout = readTimeout == null ? Duration.ofSeconds(10) : readTimeout;
    }
}
