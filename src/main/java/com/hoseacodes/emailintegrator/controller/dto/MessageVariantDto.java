package com.hoseacodes.emailintegrator.controller.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * A per-recipient-group override, letting one request deliver differing content to different
 * recipients without the caller issuing N requests.
 *
 * <p>{@code subject} and {@code htmlContent} are optional and inherit from the enclosing
 * {@link SendEmailRequest} when omitted.
 */
public record MessageVariantDto(

        @NotEmpty(message = "a variant requires at least one recipient")
        @Size(max = 50, message = "no more than 50 recipients per variant")
        List<@Valid Recipient> to,

        @Size(max = 998, message = "subject must not exceed 998 characters")
        String subject,

        @Size(max = 500_000, message = "htmlContent must not exceed 500000 characters")
        String htmlContent) {
}
