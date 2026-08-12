package com.hoseacodes.emailintegrator.email;

import java.time.Duration;

/**
 * A provider failure, translated into terms the application understands.
 *
 * <p>Provider adapters catch their vendor's exception types and throw this instead, so nothing
 * above the integration boundary needs to know that Brevo exists — let alone what an
 * {@code ApiException} is.
 *
 * <h2>Why one exception with an enum rather than a class hierarchy</h2>
 * Nothing in this application selectively {@code catch}es one failure kind and not another; the
 * only thing that varies is how each maps to an HTTP status and whether a retry is safe. A
 * switch over {@link Reason} expresses that directly, and stays readable as a single file.
 * A hierarchy would be the better choice if callers needed distinct recovery paths per type.
 */
public class EmailProviderException extends RuntimeException {

    /**
     * Why the send failed.
     *
     * <p>{@code sideEffectPossible} is the important field and the reason this enum exists in
     * this shape. It answers the only question that matters before retrying a non-idempotent
     * operation: <em>might the email already have been sent?</em>
     *
     * <p>A failure where the provider never received the request can be retried safely. A
     * failure where the request was delivered but the outcome is unknown cannot — retrying
     * sends a human a second copy. See {@code docs/RELIABILITY.md}.
     */
    public enum Reason {

        /** Provider rejected the request as invalid (HTTP 400). Our bug or the caller's; retrying is pointless. */
        REQUEST_REJECTED(false),

        /** Our API key is missing, wrong, or revoked (HTTP 401/403). A configuration fault, not a caller fault. */
        PROVIDER_AUTH_FAILED(false),

        /** Provider quota or rate limit exceeded (HTTP 429). Retryable, but only after the indicated delay. */
        RATE_LIMITED(false),

        /**
         * Provider returned a server error (HTTP 5xx). Treated as side-effect-possible: a 500 can
         * be raised after the message was queued, so a blind retry risks a duplicate send.
         */
        PROVIDER_UNAVAILABLE(true),

        /**
         * The request was written but no response arrived in time (read timeout). The provider
         * may well have accepted and sent it. Never blind-retry this.
         */
        TIMEOUT(true),

        /**
         * The TCP connection was never established — connection refused, DNS failure, connect
         * timeout. The provider cannot have seen the request, so this one <em>is</em> safe to retry.
         */
        CONNECT_FAILED(false);

        private final boolean sideEffectPossible;

        Reason(boolean sideEffectPossible) {
            this.sideEffectPossible = sideEffectPossible;
        }

        /** Whether the email may already have been sent despite this failure. */
        public boolean isSideEffectPossible() {
            return sideEffectPossible;
        }
    }

    private final Reason reason;
    private final String provider;
    private final Duration retryAfter;

    public EmailProviderException(Reason reason, String provider, String message) {
        this(reason, provider, message, null, null);
    }

    public EmailProviderException(Reason reason, String provider, String message, Throwable cause) {
        this(reason, provider, message, null, cause);
    }

    public EmailProviderException(Reason reason,
                                  String provider,
                                  String message,
                                  Duration retryAfter,
                                  Throwable cause) {
        super(message, cause);
        this.reason = reason;
        this.provider = provider;
        this.retryAfter = retryAfter;
    }

    public Reason getReason() {
        return reason;
    }

    public String getProvider() {
        return provider;
    }

    /** How long to wait before retrying, when the provider told us (HTTP 429 Retry-After). */
    public Duration getRetryAfter() {
        return retryAfter;
    }

    /** Convenience accessor: whether the email may already have been sent. */
    public boolean isSideEffectPossible() {
        return reason.isSideEffectPossible();
    }
}
