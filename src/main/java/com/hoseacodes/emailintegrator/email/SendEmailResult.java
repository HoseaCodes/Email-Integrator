package com.hoseacodes.emailintegrator.email;

import java.util.List;

/**
 * The outcome of a successful send, in the application's own terms.
 *
 * <p>{@code messageIds} is a list because a multi-variant send yields one id per variant. A
 * single send returns exactly one. Callers keep these ids to correlate with provider-side
 * delivery events and support tickets.
 *
 * @param messageIds provider-assigned message identifiers, in variant order
 * @param provider   which integration handled the send, e.g. {@code "brevo"}
 */
public record SendEmailResult(List<String> messageIds, String provider) {

    public SendEmailResult {
        messageIds = messageIds == null ? List.of() : List.copyOf(messageIds);
    }

    public static SendEmailResult single(String messageId, String provider) {
        return new SendEmailResult(messageId == null ? List.of() : List.of(messageId), provider);
    }
}
