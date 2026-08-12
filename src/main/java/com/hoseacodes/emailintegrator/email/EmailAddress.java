package com.hoseacodes.emailintegrator.email;

/**
 * An email address with an optional display name.
 *
 * <p>This is the application's own representation. Provider adapters translate it into
 * whatever shape their API expects, so no provider wire format leaks into the service layer.
 */
public record EmailAddress(String email, String name) {

    public EmailAddress {
        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("email address must not be blank");
        }
        email = email.trim();
        name = (name == null || name.isBlank()) ? null : name.trim();
    }

    public static EmailAddress of(String email) {
        return new EmailAddress(email, null);
    }
}
