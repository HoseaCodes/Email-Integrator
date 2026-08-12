package com.hoseacodes.emailintegrator.email;

import java.util.List;

/**
 * What the application wants sent, expressed independently of any provider.
 *
 * <p>Built by the service layer — never deserialized straight from an HTTP body. Keeping the
 * public API DTO and this command separate means the wire contract can change without
 * reshaping the integration layer, and vice versa.
 *
 * <p>The sender is supplied by the application from configuration, not by the caller. Allowing
 * callers to choose their own {@code From} on a shared, authenticated sending identity invites
 * spoofing and burns domain reputation.
 */
public record SendEmailCommand(
        EmailAddress sender,
        List<EmailAddress> to,
        List<EmailAddress> cc,
        List<EmailAddress> bcc,
        EmailAddress replyTo,
        String subject,
        String htmlContent,
        String textContent,
        List<MessageVariant> variants) {

    public SendEmailCommand {
        if (sender == null) {
            throw new IllegalArgumentException("sender is required");
        }
        if (subject == null || subject.isBlank()) {
            throw new IllegalArgumentException("subject is required");
        }
        if ((htmlContent == null || htmlContent.isBlank())
                && (textContent == null || textContent.isBlank())) {
            throw new IllegalArgumentException("either htmlContent or textContent is required");
        }

        to = to == null ? List.of() : List.copyOf(to);
        cc = cc == null ? List.of() : List.copyOf(cc);
        bcc = bcc == null ? List.of() : List.copyOf(bcc);
        variants = variants == null ? List.of() : List.copyOf(variants);

        // Recipients live either at the top level or inside variants, never neither.
        if (to.isEmpty() && variants.isEmpty()) {
            throw new IllegalArgumentException("at least one recipient is required");
        }
    }

    /** True when this send fans out to per-recipient variants rather than a single message. */
    public boolean isMultiVariant() {
        return !variants.isEmpty();
    }

    /** Total recipient count across the top level and all variants — used for logging and metrics. */
    public int totalRecipientCount() {
        return to.size() + cc.size() + bcc.size()
                + variants.stream().mapToInt(v -> v.to().size()).sum();
    }
}
