package com.hoseacodes.emailintegrator.service;

import com.hoseacodes.emailintegrator.config.EmailProperties;
import com.hoseacodes.emailintegrator.email.EmailAddress;
import com.hoseacodes.emailintegrator.email.EmailProvider;
import com.hoseacodes.emailintegrator.email.SendEmailCommand;
import com.hoseacodes.emailintegrator.email.SendEmailResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Application-level entry point for sending email through an external provider.
 *
 * <p>Thin on purpose, but no longer a pass-through. It owns the decisions that belong to the
 * application rather than to either the API or the provider:
 *
 * <ul>
 *   <li>the sending identity, taken from configuration and never from the caller;</li>
 *   <li>the {@code app.email.enabled} kill switch;</li>
 *   <li>the operational log line marking one logical send attempt.</li>
 * </ul>
 *
 * <p>It depends on the {@link EmailProvider} interface, not on any concrete provider, so it
 * contains no Brevo types and no knowledge of HTTP. The previous version injected
 * {@code BrevoEmailDelegate} directly and wrapped it in {@code catch (Exception e) { throw e; }},
 * which added a layer without adding a boundary.
 */
@Service
public class EmailDeliveryService {

    private static final Logger log = LoggerFactory.getLogger(EmailDeliveryService.class);

    private final EmailProvider emailProvider;
    private final EmailProperties emailProperties;

    public EmailDeliveryService(EmailProvider emailProvider, EmailProperties emailProperties) {
        this.emailProvider = emailProvider;
        this.emailProperties = emailProperties;
    }

    /**
     * Sends one message.
     *
     * @param draft everything about the message except the sender, which this method supplies
     * @return provider-assigned message identifiers
     * @throws EmailSendingDisabledException if sending is switched off by configuration
     * @throws com.hoseacodes.emailintegrator.email.EmailProviderException if the provider
     *         rejected, throttled, failed, or did not respond
     */
    public SendEmailResult send(EmailDraft draft) {
        if (!emailProperties.isEnabled()) {
            log.warn("Send rejected: email sending is disabled by configuration");
            throw new EmailSendingDisabledException();
        }

        SendEmailCommand command = new SendEmailCommand(
                configuredSender(),
                draft.to(),
                draft.cc(),
                draft.bcc(),
                draft.replyTo(),
                draft.subject(),
                draft.htmlContent(),
                draft.textContent(),
                draft.variants());

        // Recipient counts, not recipient addresses: enough to reason about load and fan-out
        // without writing personal data into every log line.
        log.info("Sending email via {}: recipients={} variants={}",
                emailProvider.name(), command.totalRecipientCount(), command.variants().size());

        return emailProvider.send(command);
    }

    private EmailAddress configuredSender() {
        String address = emailProperties.getDefaultFromAddress();
        if (address == null || address.isBlank()) {
            throw new IllegalStateException(
                    "app.email.default-from-address is not configured; refusing to send without a sender");
        }
        return new EmailAddress(address, emailProperties.getDefaultFromName());
    }
}
