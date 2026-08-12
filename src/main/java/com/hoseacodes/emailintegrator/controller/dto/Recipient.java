package com.hoseacodes.emailintegrator.controller.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * An addressee in a send request.
 *
 * <p>{@code @Email} catches structurally invalid addresses before a request reaches the
 * provider. It cannot prove an address exists — only a delivery attempt does that — but it
 * turns the common typo into a clear 400 instead of a provider round-trip and a 502.
 *
 * @param email address, validated for format and RFC 5321 length
 * @param name  optional display name
 */
public record Recipient(

        @NotBlank(message = "recipient email is required")
        @Email(message = "must be a well-formed email address")
        @Size(max = 254, message = "email address must not exceed 254 characters")
        String email,

        @Size(max = 100, message = "recipient name must not exceed 100 characters")
        String name) {
}
