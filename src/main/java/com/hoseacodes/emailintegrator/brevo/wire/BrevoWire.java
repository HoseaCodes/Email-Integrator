package com.hoseacodes.emailintegrator.brevo.wire;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Brevo's on-the-wire JSON shapes for {@code POST /v3/smtp/email}.
 *
 * <p>Grouped in one file because they are a single cohesive contract and each is two or three
 * lines; splitting them across six files would add navigation cost without adding clarity.
 *
 * <p>These types are intentionally confined to the {@code brevo} package. They must never
 * appear in a controller signature or in a service-layer type — that coupling is precisely
 * what the previous implementation got wrong, where {@code EMSBatchResponse extends
 * EmailResponse} made Brevo's JSON part of this service's public API.
 *
 * <p>{@code @JsonInclude(NON_NULL)} matters on the request types: Brevo rejects some fields when
 * present-but-null, so omitting them is not merely cosmetic.
 */
public final class BrevoWire {

    private BrevoWire() {
    }

    /** A sender, recipient, or reply-to address. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Contact(String email, String name) {
    }

    /** One per-recipient override in a multi-variant send. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MessageVersion(List<Contact> to, String subject, String htmlContent) {
    }

    /** Request body for {@code POST /v3/smtp/email}. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SendRequest(
            Contact sender,
            List<Contact> to,
            List<Contact> cc,
            List<Contact> bcc,
            Contact replyTo,
            String subject,
            String htmlContent,
            String textContent,
            List<MessageVersion> messageVersions) {
    }

    /**
     * Success body. Brevo returns {@code messageId} for a single send and {@code messageIds}
     * for a multi-variant send, so both are modelled and exactly one will be populated.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SendResponse(String messageId, List<String> messageIds) {

        /** Normalises either response form into a single list. */
        public List<String> allMessageIds() {
            if (messageIds != null && !messageIds.isEmpty()) {
                return messageIds;
            }
            return messageId == null ? List.of() : List.of(messageId);
        }
    }

    /**
     * Brevo's error body, e.g. {@code {"code":"invalid_parameter","message":"..."}}.
     *
     * <p>Deliberately not forwarded verbatim to API callers: it can name internal parameters
     * and account details. It is logged server-side and summarised in the response.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ErrorResponse(String code, String message) {
    }
}
