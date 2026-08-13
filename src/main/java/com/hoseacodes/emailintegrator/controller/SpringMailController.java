package com.hoseacodes.emailintegrator.controller;

import com.hoseacodes.emailintegrator.controller.dto.SendEmailResponse;
import com.hoseacodes.emailintegrator.controller.dto.SendMailRequest;
import com.hoseacodes.emailintegrator.email.SendEmailResult;
import com.hoseacodes.emailintegrator.service.SpringMailService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Direct SMTP sending, for messages that do not go through the external provider.
 *
 * <p>Matches {@link EmailController}: validate, delegate, translate. No {@code try/catch} — the
 * previous version wrapped every method in one and returned {@code "Internal server error: " +
 * e.getMessage()}, which produced a different error shape from every other endpoint and leaked
 * SMTP hostnames and server rejection text to callers. Failures are now translated by
 * {@link ApiExceptionHandler}.
 *
 * <p>{@code POST /send-simple} was removed. It took an untyped {@code Map<String,String>} and did
 * a strict subset of what this endpoint does, so it offered a second, unvalidated, undocumented
 * way to do the same thing. Two overlapping endpoints on one resource is a contract to maintain
 * forever in exchange for nothing.
 */
@RestController
@RequestMapping("/api/spring-mail")
public class SpringMailController {

    private final SpringMailService springMailService;

    public SpringMailController(SpringMailService springMailService) {
        this.springMailService = springMailService;
    }

    /**
     * Sends an email over SMTP.
     *
     * <p>Returns <b>202 Accepted</b>, consistent with {@code POST /email}. The mail server has
     * accepted the message for delivery; it has not necessarily reached a mailbox, and it can
     * still bounce afterwards.
     *
     * @return the SMTP {@code Message-ID}, which is what appears in mail server logs and in the
     *         recipient's headers — so it is the id worth keeping to trace a message later
     */
    @PostMapping(path = "/send",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    public SendEmailResponse sendEmail(@Valid @RequestBody SendMailRequest request) {
        SendEmailResult result = springMailService.send(request);
        return new SendEmailResponse(result.messageIds(), result.provider());
    }
}
