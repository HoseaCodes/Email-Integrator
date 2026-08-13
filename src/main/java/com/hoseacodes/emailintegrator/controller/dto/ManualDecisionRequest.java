package com.hoseacodes.emailintegrator.controller.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code POST /auth/manual-approve} and {@code /auth/manual-deny}, where an
 * administrator records a decision directly rather than by clicking an emailed link.
 *
 * <p>These were the endpoints whose Javadoc said "by admin" while the code required no
 * credentials at all (ENGINEERING_AUDIT CRIT-1). They now require an API key, and this replaces
 * the untyped {@code Map<String,String>} body.
 *
 * @param email the user the decision applies to
 * @param name  display name for the notification email; defaults when absent
 */
public record ManualDecisionRequest(

        @NotBlank(message = "email is required")
        @Email(message = "must be a well-formed email address")
        @Size(max = 254)
        String email,

        @Size(max = 100)
        String name) {

    /** Display name to use in the outgoing message when the caller did not supply one. */
    public String nameOrDefault() {
        return (name == null || name.isBlank()) ? "User" : name;
    }
}
