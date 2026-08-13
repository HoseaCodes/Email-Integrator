package com.hoseacodes.emailintegrator.controller.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Public request body for {@code POST /api/spring-mail/send}, which delivers over Gmail SMTP.
 *
 * <p>Deliberately a separate type from {@link SendEmailRequest} rather than a shared one. The two
 * endpoints have genuinely different capabilities — the Brevo path supports per-recipient
 * variants, SMTP does not — and collapsing them would mean publishing a field that must always be
 * empty on one of them, which is worse than a little structural similarity. They share the
 * {@link Recipient} type, which is where the real duplication would otherwise be.
 *
 * <p>Like the Brevo request, there is no {@code from} field. The previous version of this endpoint
 * accepted one and passed it straight to {@code MimeMessageHelper.setFrom}, which let any caller
 * choose the sending identity on a shared, authenticated Gmail account — and set an arbitrary
 * {@code replyTo}, which is enough for conversation hijacking even when the provider rewrites
 * {@code From}. See ENGINEERING_AUDIT HIGH-9.
 */
public record SendMailRequest(

        @NotEmpty(message = "at least one recipient is required")
        @Size(max = 50, message = "no more than 50 primary recipients per request")
        List<@Valid Recipient> to,

        @Size(max = 50, message = "no more than 50 cc recipients per request")
        List<@Valid Recipient> cc,

        @Size(max = 50, message = "no more than 50 bcc recipients per request")
        List<@Valid Recipient> bcc,

        @Valid Recipient replyTo,

        @NotBlank(message = "subject is required")
        @Size(max = 998, message = "subject must not exceed 998 characters")
        String subject,

        @Size(max = 500_000, message = "htmlContent must not exceed 500000 characters")
        String htmlContent,

        @Size(max = 500_000, message = "textContent must not exceed 500000 characters")
        String textContent) {

    /** A message needs a body in at least one format. */
    @AssertTrue(message = "either htmlContent or textContent must be provided")
    public boolean isBodyPresent() {
        return (htmlContent != null && !htmlContent.isBlank())
                || (textContent != null && !textContent.isBlank());
    }
}
