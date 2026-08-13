package com.hoseacodes.emailintegrator.service;

import com.hoseacodes.emailintegrator.config.EmailProperties;
import com.hoseacodes.emailintegrator.controller.dto.Recipient;
import com.hoseacodes.emailintegrator.controller.dto.SendMailRequest;
import com.hoseacodes.emailintegrator.email.EmailProviderException;
import com.hoseacodes.emailintegrator.email.EmailProviderException.Reason;
import com.hoseacodes.emailintegrator.email.SendEmailResult;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailException;
import org.springframework.mail.MailParseException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.UnsupportedEncodingException;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Sends mail directly over SMTP.
 *
 * <p>Brought in line with the Brevo path, which had diverged into two different styles of failure
 * handling in the same codebase. Specifically:
 *
 * <ul>
 *   <li><b>Failures throw rather than returning a success flag.</b> The previous version returned
 *       {@code SimpleEmailResponse(success=false, message=e.getMessage())}, which discarded the
 *       distinction between "rejected" and "might have been delivered", and leaked SMTP hostnames
 *       and server rejection text to the caller. Failures now become
 *       {@link EmailProviderException} and are translated centrally, so an SMTP failure and a
 *       Brevo failure produce the same error contract.</li>
 *   <li><b>The sender comes from configuration.</b> Callers no longer choose it.</li>
 *   <li><b>The returned id is real.</b> It was a freshly generated UUID that corresponded to
 *       nothing — worse than no id, because it looks traceable. It is now the SMTP
 *       {@code Message-ID} assigned to the outgoing message, which is what actually appears in
 *       mail logs and in the recipient's headers.</li>
 * </ul>
 */
@Service
public class SpringMailService {

    static final String PROVIDER_NAME = "gmail-smtp";

    private static final Logger log = LoggerFactory.getLogger(SpringMailService.class);

    private final JavaMailSender mailSender;
    private final EmailProperties emailProperties;

    public SpringMailService(JavaMailSender mailSender, EmailProperties emailProperties) {
        this.mailSender = mailSender;
        this.emailProperties = emailProperties;
    }

    /**
     * Sends one message over SMTP.
     *
     * @throws EmailSendingDisabledException if {@code app.email.enabled} is false
     * @throws EmailProviderException        if the mail server rejected the message or could not
     *                                       be reached. Check
     *                                       {@link EmailProviderException#isSideEffectPossible()}
     *                                       before considering a retry.
     */
    public SendEmailResult send(SendMailRequest request) {
        if (!emailProperties.isEnabled()) {
            log.warn("Send rejected: email sending is disabled by configuration");
            throw new EmailSendingDisabledException();
        }

        String fromAddress = configuredSender();
        long startNanos = System.nanoTime();
        String messageId = null;

        try {
            MimeMessage mimeMessage = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, true, "UTF-8");

            if (StringUtils.hasText(emailProperties.getDefaultFromName())) {
                helper.setFrom(fromAddress, emailProperties.getDefaultFromName());
            } else {
                helper.setFrom(fromAddress);
            }

            helper.setTo(addresses(request.to()));
            if (request.cc() != null && !request.cc().isEmpty()) {
                helper.setCc(addresses(request.cc()));
            }
            if (request.bcc() != null && !request.bcc().isEmpty()) {
                helper.setBcc(addresses(request.bcc()));
            }
            if (request.replyTo() != null) {
                helper.setReplyTo(request.replyTo().email());
            }

            helper.setSubject(request.subject());

            // Both formats when available, so clients that cannot render HTML still get content.
            if (StringUtils.hasText(request.htmlContent())) {
                helper.setText(
                        request.textContent() == null ? "" : request.textContent(),
                        request.htmlContent());
            } else {
                helper.setText(request.textContent());
            }

            // Assign the Message-ID before transmitting rather than reading it back afterwards.
            //
            // saveChanges() is what generates the header; JavaMailSenderImpl would call it during
            // send anyway, so this changes nothing about the message. What it buys is that the id
            // exists *before* the network call — so when a send times out and delivery is
            // genuinely unknown, there is still an identifier to search the mail server's logs
            // for. Reading it back after a failed send would give nothing, which is exactly the
            // case where it is most needed.
            mimeMessage.saveChanges();
            messageId = readMessageId(mimeMessage);

            mailSender.send(mimeMessage);

            log.info("SMTP send succeeded: recipients={} messageId={} durationMs={}",
                    recipientCount(request), messageId, elapsedMillis(startNanos));

            return SendEmailResult.single(messageId, PROVIDER_NAME);

        } catch (MailAuthenticationException e) {
            // Our SMTP credentials are wrong. Not the caller's problem, so this becomes a 502.
            throw failure(Reason.PROVIDER_AUTH_FAILED, "SMTP authentication failed",
                    e, startNanos, messageId);

        } catch (MailParseException | MessagingException | UnsupportedEncodingException e) {
            // The message could not be assembled — a malformed address that slipped past
            // validation, or an unencodable header. Nothing was transmitted.
            throw failure(Reason.REQUEST_REJECTED, "the message could not be composed",
                    e, startNanos, messageId);

        } catch (MailException e) {
            // Everything else: connection failures, server rejections, partial delivery.
            //
            // Classified as side-effect-possible on purpose. SMTP delivery is not atomic — a
            // failure can be raised after the server has accepted the message for some recipients,
            // and MailSendException explicitly models per-recipient failures. Treating this as
            // "definitely not sent" would license a retry that duplicates mail already delivered.
            throw failure(Reason.PROVIDER_UNAVAILABLE, "the mail server could not deliver the message",
                    e, startNanos, messageId);
        }
    }

    private EmailProviderException failure(Reason reason, String detail, Exception cause,
                                           long startNanos, String messageId) {
        // Detail, timing, and the Message-ID are logged. The Message-ID matters most on a
        // side-effect-possible failure: it is the only handle for checking after the fact whether
        // the message actually went out.
        //
        // The exception message itself carries no SMTP host, port, or server response text,
        // because it may reach an API response.
        log.warn("SMTP send failed: reason={} sideEffectPossible={} messageId={} durationMs={} detail={}",
                reason, reason.isSideEffectPossible(), messageId, elapsedMillis(startNanos),
                cause.getMessage());
        return new EmailProviderException(reason, PROVIDER_NAME, detail, cause);
    }

    /**
     * Reads the {@code Message-ID} that JavaMail assigned during send.
     *
     * <p>Best effort: the message was already sent by this point, so failing to read its id must
     * not turn a successful send into an error.
     */
    private String readMessageId(MimeMessage mimeMessage) {
        try {
            return mimeMessage.getMessageID();
        } catch (MessagingException e) {
            log.debug("Could not read Message-ID from a sent message", e);
            return null;
        }
    }

    private String configuredSender() {
        String address = emailProperties.getDefaultFromAddress();
        if (!StringUtils.hasText(address)) {
            throw new IllegalStateException(
                    "app.email.default-from-address is not configured; refusing to send without a sender");
        }
        return address;
    }

    private static String[] addresses(List<Recipient> recipients) {
        return recipients.stream().map(Recipient::email).toArray(String[]::new);
    }

    private static int recipientCount(SendMailRequest request) {
        return request.to().size()
                + (request.cc() == null ? 0 : request.cc().size())
                + (request.bcc() == null ? 0 : request.bcc().size());
    }

    private static long elapsedMillis(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }
}
