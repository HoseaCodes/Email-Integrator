package com.hoseacodes.emailintegrator.service;

import com.hoseacodes.emailintegrator.email.EmailProviderException;
import com.hoseacodes.emailintegrator.email.EmailProviderException.Reason;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailParseException;

/**
 * Translates SMTP failures into the application's provider-failure model.
 *
 * <p>Shared by every code path that talks to a mail server, so an SMTP failure is classified the
 * same way regardless of which service raised it. Previously the two SMTP services each had their
 * own handling — one threw, the other returned {@code false} — which meant the same underlying
 * failure produced a 502 from one endpoint and a 400 from another.
 *
 * <p>The classification that matters is {@link Reason#isSideEffectPossible()}. See
 * {@link #translate} for why a generic send failure is treated as possibly-delivered.
 */
final class SmtpFailures {

    private SmtpFailures() {
    }

    /**
     * Maps an exception raised while composing or sending a message.
     *
     * @param cause    the original failure
     * @param provider provider name for the resulting exception, e.g. {@code "gmail-smtp"}
     * @param detail   caller-safe description. Must not contain hostnames, ports, account
     *                 identifiers, or the server's response text — it may reach an API response.
     */
    static EmailProviderException translate(Exception cause, String provider, String detail) {
        Reason reason = classify(cause);
        return new EmailProviderException(reason, provider, detail, cause);
    }

    /** Maps an exception using the default caller-safe wording for its classification. */
    static EmailProviderException translate(Exception cause, String provider) {
        return translate(cause, provider, detailFor(classify(cause)));
    }

    /**
     * Caller-safe description for a classification.
     *
     * <p>Kept here so every SMTP path words the same failure identically, and so there is one
     * place to check that none of these strings can carry a hostname or server response.
     */
    static String detailFor(Reason reason) {
        return switch (reason) {
            case PROVIDER_AUTH_FAILED -> "the mail service could not authenticate with its provider";
            case REQUEST_REJECTED -> "the message could not be composed";
            default -> "the mail server could not deliver the message";
        };
    }

    static Reason classify(Exception cause) {
        // Our SMTP credentials are wrong. A configuration fault, not the caller's — so this must
        // not surface as 401, which would imply the caller should re-authenticate.
        if (cause instanceof MailAuthenticationException) {
            return Reason.PROVIDER_AUTH_FAILED;
        }

        // The message could not be assembled: a malformed address that slipped past validation,
        // an unencodable header. Nothing was transmitted.
        if (cause instanceof MailParseException
                || cause instanceof jakarta.mail.MessagingException
                || cause instanceof java.io.UnsupportedEncodingException) {
            return Reason.REQUEST_REJECTED;
        }

        // Everything else: connection failures, server rejections, partial delivery.
        //
        // Deliberately side-effect-possible. SMTP delivery is not atomic — MailSendException
        // explicitly models per-recipient failures, so the server may already have accepted the
        // message for some recipients before the error was raised. Classifying this as
        // definitely-not-sent would license a retry that duplicates mail already delivered.
        return Reason.PROVIDER_UNAVAILABLE;
    }
}
