package com.hoseacodes.emailintegrator.controller.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Public request body for {@code POST /email}.
 *
 * <p>Deliberately not the same type as {@code SendEmailCommand}. The DTO is the published wire
 * contract and changes only with an API version; the command is an internal shape free to
 * change with the integration. Collapsing the two saves a mapping method and costs the freedom
 * to evolve either side independently.
 *
 * <p>There is no {@code from} field, and that is intentional. All mail leaves under one
 * configured, provider-verified sending identity. Letting callers choose their own {@code From}
 * on a shared authenticated sender enables spoofing and puts the domain's delivery reputation
 * in the hands of every client.
 *
 * <p>Size limits are not arbitrary: they bound memory per request on a small instance and
 * reflect real constraints — 254 characters is the maximum length of an email address
 * (RFC 5321), and 998 is the maximum line length for a header field (RFC 5322).
 */
public record SendEmailRequest(

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
        String textContent,

        @Size(max = 50, message = "no more than 50 message variants per request")
        List<@Valid MessageVariantDto> variants) {

    /**
     * Cross-field rule: a message needs a body in at least one format.
     *
     * <p>Expressed as an {@code @AssertTrue}-style derived property so it is reported through
     * the same validation pipeline as every other constraint, and therefore lands in the same
     * {@code validationErrors} block of the error response rather than as an ad-hoc 400.
     */
    @jakarta.validation.constraints.AssertTrue(
            message = "either htmlContent or textContent must be provided")
    public boolean isBodyPresent() {
        return (htmlContent != null && !htmlContent.isBlank())
                || (textContent != null && !textContent.isBlank());
    }
}
