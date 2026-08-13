package com.hoseacodes.emailintegrator.controller.dto;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code POST /auth/send-email}, discriminated by {@code templateType}.
 *
 * <h2>Why a sealed type rather than a Map</h2>
 * These payloads are genuinely different: an approval request needs approve and deny URLs, a
 * password reset needs a reset URL, a consultation needs a date and a meeting link. The previous
 * implementation modelled that as {@code Map<String,Object>} plus roughly 140 lines of casting,
 * null-checking, and a nested switch in the controller. That cost three things: no validation, no
 * OpenAPI schema, and no way for a caller to discover what a given {@code templateType} requires
 * without reading the source.
 *
 * <p>Modelling it as a sealed interface gives all three back. Sealing also fixes the permitted set
 * at compile time, which is what makes the Jackson subtype mapping below safe and keeps it closed.
 *
 * <p>On Java 21 the service's dispatch could be a pattern-matching switch, which the compiler
 * would check for exhaustiveness. This project targets Java 17, where that is still a preview
 * feature, so coverage of every subtype is asserted by a test instead.
 *
 * <h2>On polymorphic deserialization</h2>
 * Jackson polymorphism is worth being careful with — deducing types from attacker-controlled
 * input is how deserialization vulnerabilities happen. This is the safe form: the permitted types
 * are a closed set fixed at compile time by {@link JsonSubTypes}, and the wire format carries a
 * short name from that set, never a class name. An unrecognised value is rejected before any
 * object is constructed.
 *
 * <p>{@code templateType} values are matched exactly and are lower-case.
 */
@JsonTypeInfo(
        use = JsonTypeInfo.Id.NAME,
        include = JsonTypeInfo.As.PROPERTY,
        property = "templateType")
@JsonSubTypes({
        @JsonSubTypes.Type(value = TemplatedEmailRequest.ApprovalRequest.class, name = "approval"),
        @JsonSubTypes.Type(value = TemplatedEmailRequest.AccountApproved.class, name = "approved"),
        @JsonSubTypes.Type(value = TemplatedEmailRequest.AccountDenied.class, name = "denied"),
        @JsonSubTypes.Type(value = TemplatedEmailRequest.RegistrationPending.class, name = "pending"),
        @JsonSubTypes.Type(value = TemplatedEmailRequest.PasswordReset.class, name = "password-reset"),
        @JsonSubTypes.Type(value = TemplatedEmailRequest.ConsultationConfirmation.class,
                name = "consultation-confirmation"),
        @JsonSubTypes.Type(value = TemplatedEmailRequest.ConsultationNotification.class,
                name = "consultation-notification")
})
public sealed interface TemplatedEmailRequest {

    /** The discriminator value, echoed back in the response and used in logs. */
    String templateType();

    /** Who the resulting email is addressed to. Used for logging and the response body. */
    String recipient();

    // -- account workflow ---------------------------------------------------------------------

    /**
     * Asks an administrator to approve a new registration. Sent to the configured admin address,
     * not to the registering user.
     *
     * <p>{@code approvalUrl} and {@code denyUrl} are rendered as links in the email and are
     * validated for scheme and host by {@code LinkSanitizer} before sending.
     */
    record ApprovalRequest(
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(max = 100) String name,
            @Size(max = 100) String appName,
            @Size(max = 150) String appDisplayName,
            @NotBlank @Size(max = 2048) String approvalUrl,
            @NotBlank @Size(max = 2048) String denyUrl)
            implements TemplatedEmailRequest {

        @Override
        public String templateType() {
            return "approval";
        }

        @Override
        public String recipient() {
            // The registering user's address identifies the request; the message itself goes to
            // the administrator, which the service resolves from configuration.
            return email;
        }
    }

    /** Tells a user their account was approved. */
    record AccountApproved(
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(max = 100) String name,
            @Size(max = 100) String appName,
            @Size(max = 150) String appDisplayName,
            @Size(max = 2048) String loginUrl)
            implements TemplatedEmailRequest {

        @Override
        public String templateType() {
            return "approved";
        }

        @Override
        public String recipient() {
            return email;
        }
    }

    /** Tells a user their registration was declined. */
    record AccountDenied(
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(max = 100) String name,
            @Size(max = 100) String appName,
            @Size(max = 150) String appDisplayName)
            implements TemplatedEmailRequest {

        @Override
        public String templateType() {
            return "denied";
        }

        @Override
        public String recipient() {
            return email;
        }
    }

    /** Confirms to a user that their registration is awaiting review. */
    record RegistrationPending(
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(max = 100) String name,
            @Size(max = 100) String appName,
            @Size(max = 150) String appDisplayName)
            implements TemplatedEmailRequest {

        @Override
        public String templateType() {
            return "pending";
        }

        @Override
        public String recipient() {
            return email;
        }
    }

    /**
     * Sends a password-reset link.
     *
     * <p>The highest-risk payload in this API: a caller-supplied URL delivered inside a security
     * email from a real sending domain. {@code resetUrl} is scheme- and host-validated before it
     * reaches the template.
     */
    record PasswordReset(
            @NotBlank @Email @Size(max = 254) String email,
            @Size(max = 100) String name,
            @NotBlank @Size(max = 2048) String resetUrl,
            @Size(max = 100) String appName,
            @Size(max = 150) String appDisplayName,
            @Size(max = 50) String expiryTime)
            implements TemplatedEmailRequest {

        @Override
        public String templateType() {
            return "password-reset";
        }

        @Override
        public String recipient() {
            return email;
        }
    }

    // -- consultations --------------------------------------------------------------------------

    /** Confirms a booked consultation to the client, with an ICS calendar attachment. */
    record ConsultationConfirmation(
            @NotBlank @Size(max = 100) String firstName,
            @NotBlank @Size(max = 100) String lastName,
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(max = 150) String company,
            @NotBlank @Size(max = 100) String consultationType,
            @NotBlank @Size(max = 10) String date,
            @NotBlank @Size(max = 10) String timeSlot,
            @NotBlank @Size(max = 2048) String meetingLink,
            @Size(max = 30) String phone,
            @Size(max = 2000) String notes)
            implements TemplatedEmailRequest {

        @Override
        public String templateType() {
            return "consultation-confirmation";
        }

        @Override
        public String recipient() {
            return email;
        }
    }

    /** Notifies the configured admin address that a consultation was booked. */
    record ConsultationNotification(
            @NotBlank @Size(max = 100) String firstName,
            @NotBlank @Size(max = 100) String lastName,
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(max = 150) String company,
            @NotBlank @Size(max = 100) String consultationType,
            @NotBlank @Size(max = 10) String date,
            @NotBlank @Size(max = 10) String timeSlot,
            @NotBlank @Size(max = 2048) String meetingLink,
            @Size(max = 30) String phone,
            @Size(max = 2000) String notes)
            implements TemplatedEmailRequest {

        @Override
        public String templateType() {
            return "consultation-notification";
        }

        @Override
        public String recipient() {
            // Goes to the administrator; the service resolves that from configuration rather
            // than from this payload. Previously it was hardcoded in two places.
            return email;
        }
    }
}
