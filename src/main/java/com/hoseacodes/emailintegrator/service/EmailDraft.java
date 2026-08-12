package com.hoseacodes.emailintegrator.service;

import com.hoseacodes.emailintegrator.email.EmailAddress;
import com.hoseacodes.emailintegrator.email.MessageVariant;

import java.util.List;

/**
 * A message as the caller described it — everything except who it comes from.
 *
 * <p>This exists to make one rule structural instead of merely documented: <b>the sender is not
 * caller input.</b> There is no {@code sender} field here, so no API-layer code can set one even
 * by accident. {@link EmailDeliveryService} supplies it from configuration when it builds the
 * {@link com.hoseacodes.emailintegrator.email.SendEmailCommand}.
 *
 * <p>Encoding an invariant in a type beats enforcing it in a code review. The alternative —
 * accepting a full command from the controller and overwriting the sender — leaves a field
 * present that must be ignored, which is exactly the kind of thing that quietly stops being
 * ignored a year later.
 */
public record EmailDraft(
        List<EmailAddress> to,
        List<EmailAddress> cc,
        List<EmailAddress> bcc,
        EmailAddress replyTo,
        String subject,
        String htmlContent,
        String textContent,
        List<MessageVariant> variants) {

    public EmailDraft {
        to = to == null ? List.of() : List.copyOf(to);
        cc = cc == null ? List.of() : List.copyOf(cc);
        bcc = bcc == null ? List.of() : List.copyOf(bcc);
        variants = variants == null ? List.of() : List.copyOf(variants);
    }
}
