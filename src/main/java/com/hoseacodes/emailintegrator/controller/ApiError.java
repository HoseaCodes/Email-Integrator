package com.hoseacodes.emailintegrator.controller;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

/**
 * The single error shape returned by every endpoint.
 *
 * <p>One shape for all failures is what makes an API programmable: clients branch on the stable
 * {@code code}, not on prose that changes with a refactor. The previous implementation returned
 * four different error shapes across three controllers, so no client could handle errors
 * generically.
 *
 * <p>Nothing here exposes stack traces, provider response bodies, SMTP hostnames, or credentials.
 * When something unexpected happens the caller receives a generic message plus {@code errorId};
 * the detail is logged server-side against that id. The caller can quote it in a support
 * request and it can be found in the logs — without handing an attacker free reconnaissance.
 *
 * @param timestamp        when the error was produced
 * @param status           HTTP status code, repeated in the body for clients that log bodies only
 * @param code             stable machine-readable identifier, e.g. {@code VALIDATION_FAILED}
 * @param message          short human-readable summary, safe to surface
 * @param path             request path that failed
 * @param errorId          correlation id, also emitted in the server log for this failure
 * @param validationErrors per-field problems; present only for validation failures
 * @param deliveryUncertain set on send failures where the email may have been delivered anyway.
 *                          {@code true} means a blind retry risks a duplicate message — see
 *                          {@code docs/RELIABILITY.md}
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(
        Instant timestamp,
        int status,
        String code,
        String message,
        String path,
        String errorId,
        List<ValidationError> validationErrors,
        Boolean deliveryUncertain) {

    /**
     * One field-level validation failure.
     *
     * @param field   dotted path to the offending field, e.g. {@code to[0].email}
     * @param message what is wrong with it
     */
    public record ValidationError(String field, String message) {
    }

    public static ApiError of(int status, String code, String message, String path, String errorId) {
        return new ApiError(Instant.now(), status, code, message, path, errorId, null, null);
    }
}
