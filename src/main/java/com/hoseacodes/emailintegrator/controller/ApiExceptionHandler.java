package com.hoseacodes.emailintegrator.controller;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.databind.exc.InvalidTypeIdException;
import com.hoseacodes.emailintegrator.email.EmailProviderException;
import com.hoseacodes.emailintegrator.service.EmailSendingDisabledException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.lang.Nullable;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.time.Instant;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Translates exceptions into the single {@link ApiError} contract.
 *
 * <p>Centralising this removes per-controller {@code try/catch} blocks, which previously
 * produced inconsistent shapes and leaked {@code e.getMessage()} — often containing SMTP hosts
 * and provider detail — straight to callers.
 *
 * <h2>Why this extends {@link ResponseEntityExceptionHandler}</h2>
 * Spring MVC raises its own exceptions for protocol-level problems: unsupported media type,
 * unsupported method, unacceptable {@code Accept} header, and so on. Each already has a correct
 * status code. An earlier version of this class declared only a catch-all
 * {@code @ExceptionHandler(Exception.class)}, which intercepted all of them and reported 415s
 * and 405s as 500 Internal Server Error — telling callers the server was broken when in fact
 * their request was. Extending the base class keeps Spring's status mapping and overrides only
 * the response <em>body</em>, so protocol errors stay accurate and still match the one error
 * shape. A test asserts the 415 case specifically.
 *
 * <h2>How provider failures map to status codes</h2>
 * The guiding principle: <em>a status code describes who needs to act.</em> 4xx tells the caller
 * to change their request; 5xx tells them the fault is ours and their request may be fine as-is.
 *
 * <ul>
 *   <li>{@code REQUEST_REJECTED} → <b>502</b>, not 400. The caller's request already passed our
 *       validation, which is the contract we published. If the provider still rejects it, the
 *       fault is in our mapping or our provider configuration — telling the caller "bad request"
 *       would send them hunting for a problem they cannot see or fix.</li>
 *   <li>{@code PROVIDER_AUTH_FAILED} → <b>502</b>, never 401. Our API key is wrong. Returning 401
 *       would imply the caller's credentials failed and invite them to re-authenticate pointlessly.</li>
 *   <li>{@code RATE_LIMITED} → <b>429</b>, forwarding {@code Retry-After} when the provider gave one.
 *       This is the one provider failure the caller genuinely can act on: slow down.</li>
 *   <li>{@code PROVIDER_UNAVAILABLE} → <b>502</b>; {@code TIMEOUT} → <b>504</b>;
 *       {@code CONNECT_FAILED} → <b>503</b>. Distinguishing these lets a caller — and a dashboard —
 *       tell "provider erroring" from "provider slow" from "provider unreachable".</li>
 * </ul>
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    // -- Spring MVC exceptions (status codes supplied by the framework) -----------------------

    /** Bean Validation failures on an {@code @Valid} request body. */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException e, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {

        List<ApiError.ValidationError> fieldErrors = e.getBindingResult().getAllErrors().stream()
                .map(error -> new ApiError.ValidationError(
                        error instanceof FieldError fe ? fe.getField() : error.getObjectName(),
                        error.getDefaultMessage()))
                .sorted(Comparator.comparing(ApiError.ValidationError::field))
                .toList();

        String path = pathOf(request);
        String errorId = newErrorId();
        log.info("Validation failed [{}] for {}: {} problem(s)", errorId, path, fieldErrors.size());

        return ResponseEntity.status(status).body(new ApiError(
                Instant.now(),
                status.value(),
                "VALIDATION_FAILED",
                "The request contains invalid or missing fields",
                path,
                errorId,
                fieldErrors,
                null));
    }

    /** Malformed JSON or an unparseable value in the request body. */
    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException e, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {

        String path = pathOf(request);
        String errorId = newErrorId();
        // Debug only: the parser message can quote the offending payload fragment.
        log.debug("Unreadable request body [{}] for {}", errorId, path, e);

        // An unrecognised discriminator is a distinct, common, and fixable mistake — not the same
        // as malformed JSON. Saying "could not be parsed" for a valid document with one wrong
        // enum-like value sends the caller looking for a syntax error that is not there.
        if (e.getCause() instanceof InvalidTypeIdException invalidType) {
            log.info("Unknown discriminator [{}] for {}", errorId, path);
            return ResponseEntity.status(status).body(ApiError.of(
                    status.value(),
                    "UNKNOWN_TEMPLATE_TYPE",
                    "Unrecognised templateType. Valid values: " + permittedTemplateTypes(invalidType),
                    path,
                    errorId));
        }

        log.info("Malformed request body [{}] for {}", errorId, path);

        return ResponseEntity.status(status).body(ApiError.of(
                status.value(),
                "MALFORMED_REQUEST",
                "Request body could not be parsed as JSON",
                path,
                errorId));
    }

    /**
     * Lists the accepted discriminator values, read from the base type's {@code @JsonSubTypes}.
     *
     * <p>Derived rather than hardcoded so the message cannot drift out of date when a subtype is
     * added — a hand-maintained list in an error message is one that is eventually wrong.
     */
    private static String permittedTemplateTypes(InvalidTypeIdException e) {
        Class<?> baseType = e.getBaseType() == null ? null : e.getBaseType().getRawClass();
        JsonSubTypes subTypes = baseType == null ? null : baseType.getAnnotation(JsonSubTypes.class);
        if (subTypes == null) {
            return "see the API documentation";
        }
        return Arrays.stream(subTypes.value())
                .map(JsonSubTypes.Type::name)
                .filter(name -> !name.isBlank())
                .sorted()
                .collect(Collectors.joining(", "));
    }

    /**
     * Every other Spring MVC exception — 415, 405, 406, 404, missing parameters.
     *
     * <p>The framework's status code is kept; only the body is replaced, so protocol errors are
     * reported accurately <em>and</em> in the same shape as everything else.
     */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception e, @Nullable Object body, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {

        String path = pathOf(request);
        String errorId = newErrorId();
        log.info("Request rejected [{}] for {}: {} ({})",
                errorId, path, status.value(), e.getClass().getSimpleName());

        return ResponseEntity.status(status).headers(headers).body(ApiError.of(
                status.value(),
                codeFor(status),
                reasonFor(status),
                path,
                errorId));
    }

    // -- application exceptions ---------------------------------------------------------------

    /** Domain invariants enforced in record constructors, e.g. a command built with no recipients. */
    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ApiError> handleIllegalArgument(IllegalArgumentException e, HttpServletRequest request) {
        String errorId = newErrorId();
        log.info("Rejected request [{}] for {}: {}", errorId, request.getRequestURI(), e.getMessage());

        return ResponseEntity.badRequest().body(ApiError.of(
                HttpStatus.BAD_REQUEST.value(),
                "INVALID_REQUEST",
                e.getMessage(),
                request.getRequestURI(),
                errorId));
    }

    /** Any failure reaching us from an email provider, already translated at the integration boundary. */
    @ExceptionHandler(EmailProviderException.class)
    ResponseEntity<ApiError> handleProviderFailure(EmailProviderException e, HttpServletRequest request) {
        String errorId = newErrorId();
        HttpStatus status = statusFor(e.getReason());

        // Full detail server-side, keyed by errorId. The caller gets the classification only.
        log.error("Provider failure [{}] provider={} reason={} sideEffectPossible={} path={}",
                errorId, e.getProvider(), e.getReason(), e.isSideEffectPossible(),
                request.getRequestURI(), e);

        ApiError body = new ApiError(
                Instant.now(),
                status.value(),
                "PROVIDER_" + e.getReason().name(),
                messageFor(e.getReason()),
                request.getRequestURI(),
                errorId,
                null,
                e.isSideEffectPossible() ? Boolean.TRUE : null);

        HttpHeaders headers = new HttpHeaders();
        if (e.getRetryAfter() != null) {
            headers.add(HttpHeaders.RETRY_AFTER, Long.toString(e.getRetryAfter().toSeconds()));
        }
        return new ResponseEntity<>(body, headers, status);
    }

    /**
     * The {@code app.email.enabled} kill switch is off.
     *
     * <p>503 rather than 500: the request was well-formed and the service is working as
     * configured — it is deliberately not sending right now. That is a temporary,
     * operator-controlled condition, which is exactly what 503 means.
     */
    @ExceptionHandler(EmailSendingDisabledException.class)
    ResponseEntity<ApiError> handleSendingDisabled(EmailSendingDisabledException e, HttpServletRequest request) {
        String errorId = newErrorId();
        log.warn("Send rejected [{}] for {}: sending is disabled by configuration",
                errorId, request.getRequestURI());

        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(ApiError.of(
                HttpStatus.SERVICE_UNAVAILABLE.value(),
                "EMAIL_SENDING_DISABLED",
                "Email sending is currently disabled.",
                request.getRequestURI(),
                errorId));
    }

    /** Anything unanticipated. The caller learns nothing about our internals beyond a correlation id. */
    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> handleUnexpected(Exception e, HttpServletRequest request) {
        String errorId = newErrorId();
        log.error("Unhandled exception [{}] for {}", errorId, request.getRequestURI(), e);

        return ResponseEntity.internalServerError().body(ApiError.of(
                HttpStatus.INTERNAL_SERVER_ERROR.value(),
                "INTERNAL_ERROR",
                "An unexpected error occurred. Quote errorId when reporting this.",
                request.getRequestURI(),
                errorId));
    }

    // -- mapping helpers ----------------------------------------------------------------------

    private static HttpStatus statusFor(EmailProviderException.Reason reason) {
        return switch (reason) {
            case RATE_LIMITED -> HttpStatus.TOO_MANY_REQUESTS;      // 429 — caller can act
            case TIMEOUT -> HttpStatus.GATEWAY_TIMEOUT;             // 504 — provider too slow
            case CONNECT_FAILED -> HttpStatus.SERVICE_UNAVAILABLE;  // 503 — provider unreachable
            case REQUEST_REJECTED, PROVIDER_AUTH_FAILED, PROVIDER_UNAVAILABLE -> HttpStatus.BAD_GATEWAY;
        };
    }

    /** Caller-facing wording. Deliberately free of provider names, hostnames, and account detail. */
    private static String messageFor(EmailProviderException.Reason reason) {
        return switch (reason) {
            case RATE_LIMITED -> "Sending is temporarily rate limited. Retry after the indicated delay.";
            case TIMEOUT -> "The email provider did not respond in time. Delivery status is unknown; "
                    + "do not retry without an idempotency key.";
            case CONNECT_FAILED -> "The email provider is currently unreachable. The message was not sent.";
            case PROVIDER_AUTH_FAILED -> "The email service is misconfigured and could not authenticate "
                    + "with its provider.";
            case REQUEST_REJECTED -> "The email provider rejected the message.";
            case PROVIDER_UNAVAILABLE -> "The email provider returned an error. Delivery status is unknown.";
        };
    }

    /** Derives a stable machine-readable code from a framework-supplied status. */
    private static String codeFor(HttpStatusCode status) {
        HttpStatus resolved = HttpStatus.resolve(status.value());
        return resolved == null
                ? "REQUEST_REJECTED"
                : resolved.name(); // e.g. UNSUPPORTED_MEDIA_TYPE, METHOD_NOT_ALLOWED
    }

    private static String reasonFor(HttpStatusCode status) {
        HttpStatus resolved = HttpStatus.resolve(status.value());
        return resolved == null ? "Request could not be processed" : resolved.getReasonPhrase();
    }

    private static String pathOf(WebRequest request) {
        return request instanceof ServletWebRequest servletRequest
                ? servletRequest.getRequest().getRequestURI()
                : "";
    }

    private static String newErrorId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
