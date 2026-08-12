package com.hoseacodes.emailintegrator.email;

/**
 * The boundary between this application and whatever actually delivers email.
 *
 * <p>There is currently one implementation. That is deliberate, and the interface is not here
 * in anticipation of more: it exists because the application layer must not depend on a
 * vendor's request shapes or exception types. Implementations own all provider-specific
 * concerns — authentication, wire format, timeouts, status-code interpretation — and expose
 * only {@link SendEmailCommand}, {@link SendEmailResult}, and {@link EmailProviderException}.
 *
 * <p>Kept deliberately narrow. No factory, registry, or strategy selector: those would be
 * scaffolding for a requirement that does not exist. Adding a second provider later means
 * writing one class and choosing between beans — a small, well-understood change.
 */
public interface EmailProvider {

    /**
     * Sends one message, blocking until the provider responds or the configured timeout elapses.
     *
     * @param command what to send; already validated by the caller
     * @return provider-assigned message identifiers
     * @throws EmailProviderException if the provider rejected, throttled, failed, or did not
     *         respond in time. Inspect {@link EmailProviderException#isSideEffectPossible()}
     *         before considering a retry — this operation is not idempotent.
     */
    SendEmailResult send(SendEmailCommand command);

    /** Short identifier used in logs, metrics, and API responses, e.g. {@code "brevo"}. */
    String name();
}
