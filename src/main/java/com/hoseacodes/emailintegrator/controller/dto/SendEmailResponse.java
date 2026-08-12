package com.hoseacodes.emailintegrator.controller.dto;

import java.util.List;

/**
 * Public response body for a successful send.
 *
 * @param messageIds provider-assigned identifiers, one per variant (one entry for a simple
 *                   send). Worth persisting client-side: these are what correlate a request
 *                   with provider-side delivery, bounce, and complaint events.
 * @param provider   which integration delivered the message, e.g. {@code "brevo"}
 */
public record SendEmailResponse(List<String> messageIds, String provider) {
}
