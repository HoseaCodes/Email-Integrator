package com.hoseacodes.emailintegrator.controller;

import com.hoseacodes.emailintegrator.controller.dto.ManualDecisionRequest;
import com.hoseacodes.emailintegrator.controller.dto.SendEmailResponse;
import com.hoseacodes.emailintegrator.controller.dto.TemplatedEmailRequest;
import com.hoseacodes.emailintegrator.controller.dto.UserDecisionResponse;
import com.hoseacodes.emailintegrator.email.SendEmailResult;
import com.hoseacodes.emailintegrator.service.ApprovalTokenService;
import com.hoseacodes.emailintegrator.service.UserApprovalEmailService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * Registration approval workflow.
 *
 * <p>Reduced from 375 lines to translation. What left: roughly 140 lines of
 * {@code Map<String,Object>} casting and null-checking (now declarative validation on
 * {@link TemplatedEmailRequest}), a nested {@code switch} on {@code templateType} (now an
 * exhaustive dispatch in the service, checked by the compiler), and a {@code try/catch} in every
 * method that produced a different error shape each time (now {@link ApiExceptionHandler}).
 *
 * <h2>Two authentication models, on purpose</h2>
 * The {@code POST} endpoints require an API key like every other endpoint. The {@code GET}
 * approve and deny endpoints do not, because they are links clicked from an email client that
 * cannot attach headers — the signed JWT in the query string is their credential, verified by
 * {@link ApprovalTokenService}. See {@code SecurityConfig}.
 *
 * <h2>Known limitation: side effects on GET</h2>
 * {@code GET /auth/approve} and {@code /auth/deny} change state and send mail, which {@code GET}
 * is not supposed to do. A mail client's link prefetcher can therefore trigger an approval nobody
 * clicked. Fixing it properly needs single-use tokens, which needs server-side token state — a
 * design decision deliberately not taken here. Recorded in ENGINEERING_AUDIT MED-6 rather than
 * quietly left.
 */
@RestController
@RequestMapping("/auth")
public class UserApprovalController {

    private static final Logger log = LoggerFactory.getLogger(UserApprovalController.class);

    private final ApprovalTokenService approvalTokenService;
    private final UserApprovalEmailService userApprovalEmailService;

    public UserApprovalController(ApprovalTokenService approvalTokenService,
                                  UserApprovalEmailService userApprovalEmailService) {
        this.approvalTokenService = approvalTokenService;
        this.userApprovalEmailService = userApprovalEmailService;
    }

    /**
     * Sends a templated email.
     *
     * <p>The body is discriminated by {@code templateType}; each type declares its own required
     * fields. An unrecognised value is rejected before any object is built.
     */
    @PostMapping(path = "/send-email",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    public SendEmailResponse sendTemplatedEmail(@Valid @RequestBody TemplatedEmailRequest request) {
        log.info("Sending '{}' email", request.templateType());
        SendEmailResult result = userApprovalEmailService.send(request);
        return new SendEmailResponse(result.messageIds(), result.provider());
    }

    /** Approves a registration from a signed link in an email. */
    @GetMapping(path = "/approve", produces = MediaType.APPLICATION_JSON_VALUE)
    public UserDecisionResponse approveUser(@RequestParam String token) {
        return applyTokenDecision(token, Decision.APPROVED);
    }

    /** Declines a registration from a signed link in an email. */
    @GetMapping(path = "/deny", produces = MediaType.APPLICATION_JSON_VALUE)
    public UserDecisionResponse denyUser(@RequestParam String token) {
        return applyTokenDecision(token, Decision.DENIED);
    }

    /** Records an approval directly, for an administrator acting outside the emailed link. */
    @PostMapping(path = "/manual-approve",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public UserDecisionResponse manuallyApproveUser(@Valid @RequestBody ManualDecisionRequest request) {
        return notifyDecision(request.email(), request.nameOrDefault(), Decision.APPROVED);
    }

    /** Records a denial directly, for an administrator acting outside the emailed link. */
    @PostMapping(path = "/manual-deny",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public UserDecisionResponse manuallyDenyUser(@Valid @RequestBody ManualDecisionRequest request) {
        return notifyDecision(request.email(), request.nameOrDefault(), Decision.DENIED);
    }

    // -- internals ------------------------------------------------------------------------------

    private enum Decision {
        APPROVED, DENIED
    }

    private UserDecisionResponse applyTokenDecision(String token, Decision decision) {
        Map<String, Object> claims = approvalTokenService.verifyApprovalTokenWithClaims(token);

        if (claims == null || claims.get("email") == null) {
            // One message for every rejection reason. Whether the token was expired, forged, or
            // simply the wrong type is useful only to someone probing the endpoint; the
            // distinction is recorded in the service's logs.
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid or expired token");
        }

        String email = (String) claims.get("email");
        String name = claims.get("name") == null ? "User" : (String) claims.get("name");

        return notifyDecision(email, name, decision);
    }

    /**
     * Notifies the user of a decision.
     *
     * <p>There is no persistence step here, and there never was — the original code carried a
     * {@code // Here you would typically update the user status in your database} comment at each
     * of these points. This service sends mail; it does not own user state. Whoever calls it is
     * responsible for recording the decision.
     */
    private UserDecisionResponse notifyDecision(String email, String name, Decision decision) {
        log.info("Recording decision {} for user", decision);

        TemplatedEmailRequest request = switch (decision) {
            case APPROVED -> new TemplatedEmailRequest.AccountApproved(email, name, null, null, null);
            case DENIED -> new TemplatedEmailRequest.AccountDenied(email, name, null, null);
        };

        // A delivery failure propagates rather than being reported as a 200 with
        // "emailSent": false, which is what the previous version did.
        SendEmailResult result = userApprovalEmailService.send(request);

        return new UserDecisionResponse(
                decision.name(),
                email,
                name,
                result.messageIds().isEmpty() ? null : result.messageIds().get(0));
    }
}
