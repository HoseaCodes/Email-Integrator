package com.hoseacodes.emailintegrator.email;

import java.util.List;

/**
 * A per-recipient-group override within a single send.
 *
 * <p>Lets one API call deliver differing subjects/bodies to different recipients — for example
 * a personalised subject line per customer — without the caller issuing N separate requests.
 *
 * <p>{@code subject} and {@code htmlContent} are optional; when null the variant inherits the
 * value from the enclosing {@link SendEmailCommand}.
 */
public record MessageVariant(
        List<EmailAddress> to,
        String subject,
        String htmlContent) {

    public MessageVariant {
        if (to == null || to.isEmpty()) {
            throw new IllegalArgumentException("a message variant must have at least one recipient");
        }
        to = List.copyOf(to);
    }
}
