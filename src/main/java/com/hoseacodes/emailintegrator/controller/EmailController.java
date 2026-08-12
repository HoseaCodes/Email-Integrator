package com.hoseacodes.emailintegrator.controller;

import com.hoseacodes.emailintegrator.controller.dto.MessageVariantDto;
import com.hoseacodes.emailintegrator.controller.dto.Recipient;
import com.hoseacodes.emailintegrator.controller.dto.SendEmailRequest;
import com.hoseacodes.emailintegrator.controller.dto.SendEmailResponse;
import com.hoseacodes.emailintegrator.email.EmailAddress;
import com.hoseacodes.emailintegrator.email.MessageVariant;
import com.hoseacodes.emailintegrator.email.SendEmailResult;
import com.hoseacodes.emailintegrator.service.EmailDeliveryService;
import com.hoseacodes.emailintegrator.service.EmailDraft;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * HTTP entry point for provider-backed email sending.
 *
 * <p>A controller's job here is narrow and it does exactly three things: accept and validate the
 * wire format, translate it into domain types, and translate the result back. It holds no
 * business rules, no provider knowledge, and — notably — no {@code try/catch}. Failures are
 * translated centrally by {@link ApiExceptionHandler}, which is what keeps the error contract
 * identical across every endpoint.
 */
@RestController
public class EmailController {

    private final EmailDeliveryService emailDeliveryService;

    public EmailController(EmailDeliveryService emailDeliveryService) {
        this.emailDeliveryService = emailDeliveryService;
    }

    /**
     * Sends an email through the configured provider.
     *
     * <p>Returns <b>202 Accepted</b> rather than 200 or 201. Handing the message to the provider
     * is not the same as delivering it to a mailbox — the provider queues it, then attempts SMTP
     * delivery over seconds or minutes, and it can still bounce. 202 says "accepted for
     * processing", which is precisely what happened. 201 Created would imply a resource this API
     * can retrieve, and there is no {@code GET /email/{id}}.
     *
     * @param request validated send request
     * @return the provider's message identifiers, for correlating later delivery events
     */
    @PostMapping(path = "/email",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    public SendEmailResponse sendEmail(@Valid @RequestBody SendEmailRequest request) {
        SendEmailResult result = emailDeliveryService.send(toDraft(request));
        return new SendEmailResponse(result.messageIds(), result.provider());
    }

    // -- wire format to domain ---------------------------------------------------------------

    private static EmailDraft toDraft(SendEmailRequest request) {
        return new EmailDraft(
                toAddresses(request.to()),
                toAddresses(request.cc()),
                toAddresses(request.bcc()),
                toAddress(request.replyTo()),
                request.subject(),
                request.htmlContent(),
                request.textContent(),
                toVariants(request.variants()));
    }

    private static List<MessageVariant> toVariants(List<MessageVariantDto> variants) {
        return variants == null ? List.of() : variants.stream()
                .map(v -> new MessageVariant(toAddresses(v.to()), v.subject(), v.htmlContent()))
                .toList();
    }

    private static List<EmailAddress> toAddresses(List<Recipient> recipients) {
        return recipients == null ? List.of() : recipients.stream()
                .map(EmailController::toAddress)
                .toList();
    }

    private static EmailAddress toAddress(Recipient recipient) {
        return recipient == null ? null : new EmailAddress(recipient.email(), recipient.name());
    }
}
