package com.hoseacodes.emailintegrator.service;

import com.hoseacodes.emailintegrator.config.EmailProperties;
import com.hoseacodes.emailintegrator.controller.dto.TemplatedEmailRequest;
import com.hoseacodes.emailintegrator.controller.dto.TemplatedEmailRequest.AccountApproved;
import com.hoseacodes.emailintegrator.controller.dto.TemplatedEmailRequest.AccountDenied;
import com.hoseacodes.emailintegrator.controller.dto.TemplatedEmailRequest.ApprovalRequest;
import com.hoseacodes.emailintegrator.controller.dto.TemplatedEmailRequest.ConsultationConfirmation;
import com.hoseacodes.emailintegrator.controller.dto.TemplatedEmailRequest.ConsultationNotification;
import com.hoseacodes.emailintegrator.controller.dto.TemplatedEmailRequest.PasswordReset;
import com.hoseacodes.emailintegrator.controller.dto.TemplatedEmailRequest.RegistrationPending;
import com.hoseacodes.emailintegrator.email.SendEmailResult;
import com.hoseacodes.emailintegrator.model.ConsultationData;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Sends the templated account-workflow and consultation emails.
 *
 * <h2>One entry point</h2>
 * {@link #send(TemplatedEmailRequest)} is the only public method, dispatching over the sealed
 * {@link TemplatedEmailRequest} hierarchy. Coverage of every permitted subtype is enforced by a
 * test rather than by the compiler — see the note on {@code send} for why the pattern-matching
 * switch that would give a compile-time guarantee is deferred until Java 21.
 *
 * <p>This replaces seven near-identical public methods, each of which opened with the same
 * enabled-check, built a {@code MimeMessage} the same way, and ended with the same
 * {@code catch (...) { return false; }}. All of that now lives once, in {@link #dispatch}.
 *
 * <h2>Failures throw</h2>
 * The previous methods returned {@code boolean}, which the controller reported as
 * {@code "emailSent": false} with HTTP 200 — so a caller could not distinguish a delivered
 * message from a silently dropped one, and got a success status either way. Failures are now
 * {@code EmailProviderException}, classified by {@link SmtpFailures} exactly as the direct SMTP
 * path classifies them.
 */
@Service
public class UserApprovalEmailService {

    private static final Logger log = LoggerFactory.getLogger(UserApprovalEmailService.class);

    private final JavaMailSender mailSender;
    private final EmailProperties emailProperties;
    private final EmailTemplateService emailTemplateService;

    @Value("${app.base-url:http://localhost:8080}")
    private String baseUrl;

    @Value("${app.admin-email:}")
    private String adminEmail;

    @Value("${app.name:Application}")
    private String appName;

    @Value("${app.display-name:User Management System}")
    private String appDisplayName;

    public UserApprovalEmailService(JavaMailSender mailSender,
                                    EmailProperties emailProperties,
                                    EmailTemplateService emailTemplateService) {
        this.mailSender = mailSender;
        this.emailProperties = emailProperties;
        this.emailTemplateService = emailTemplateService;
    }

    /**
     * Renders and sends the message described by {@code request}.
     *
     * @throws EmailSendingDisabledException                                    if sending is off
     * @throws com.hoseacodes.emailintegrator.email.EmailProviderException      if SMTP failed
     * @throws IllegalArgumentException                                         if a caller-supplied
     *         link fails {@link LinkSanitizer}'s scheme or host rules
     */
    public SendEmailResult send(TemplatedEmailRequest request) {
        if (!emailProperties.isEnabled()) {
            log.warn("Send rejected: email sending is disabled by configuration");
            throw new EmailSendingDisabledException();
        }

        // Dispatch over the sealed type.
        //
        // This wants to be a pattern-matching switch, which the compiler would then check for
        // exhaustiveness — adding a permitted subtype without handling it would fail the build,
        // and the trailing throw below would be unreachable and unnecessary. Pattern switch is a
        // preview feature on Java 17 and only became standard in Java 21, so it is deferred
        // rather than enabled with --enable-preview: preview features are explicitly not
        // guaranteed stable across releases, which is a poor trade for syntax.
        //
        // Sealing still earns its place here: it fixes the permitted set at compile time, which
        // is what makes the Jackson subtype mapping safe and keeps this list closed.
        if (request instanceof ApprovalRequest r) {
            return sendApprovalRequest(r);
        }
        if (request instanceof AccountApproved r) {
            return sendAccountApproved(r);
        }
        if (request instanceof AccountDenied r) {
            return sendAccountDenied(r);
        }
        if (request instanceof RegistrationPending r) {
            return sendRegistrationPending(r);
        }
        if (request instanceof PasswordReset r) {
            return sendPasswordReset(r);
        }
        if (request instanceof ConsultationConfirmation r) {
            return sendConsultationConfirmation(r);
        }
        if (request instanceof ConsultationNotification r) {
            return sendConsultationNotification(r);
        }
        // Only reachable if a permitted subtype is added without being handled here. A test
        // asserts every subtype is covered, standing in for the check the compiler cannot do.
        throw new IllegalStateException(
                "No handler for template type: " + request.templateType());
    }

    // -- per-template composition ---------------------------------------------------------------

    private SendEmailResult sendApprovalRequest(ApprovalRequest r) {
        String app = appNameOr(r.appName());

        String html = emailTemplateService.processTemplate("approval-email.html",
                Map.of(
                        "userName", r.name(),
                        "userEmail", r.email(),
                        // The approval token is not included: the caller supplies its own
                        // approve/deny URLs, so minting a token here would emit an unused
                        // credential into an email.
                        "approvalToken", "",
                        "appName", app,
                        "appDisplayName", displayNameOr(r.appDisplayName())),
                Map.of(
                        "approvalUrl", r.approvalUrl(),
                        "denyUrl", r.denyUrl()));

        // Goes to the administrator, resolved from configuration.
        return dispatch(requireAdminEmail(), displayNameOr(r.appDisplayName()),
                "New User Registration Approval Required - " + app, html, null);
    }

    private SendEmailResult sendAccountApproved(AccountApproved r) {
        String app = appNameOr(r.appName());
        String loginUrl = StringUtils.hasText(r.loginUrl()) ? r.loginUrl() : baseUrl + "/login";

        String html = emailTemplateService.processTemplate("account-approved.html",
                Map.of(
                        "userName", r.name(),
                        "appName", app,
                        "appDisplayName", displayNameOr(r.appDisplayName())),
                Map.of("loginUrl", loginUrl));

        return dispatch(r.email(), displayNameOr(r.appDisplayName()),
                "Your " + app + " Account Has Been Approved", html, null);
    }

    private SendEmailResult sendAccountDenied(AccountDenied r) {
        String app = appNameOr(r.appName());

        String html = emailTemplateService.processTemplate("account-denied.html",
                Map.of(
                        "userName", r.name(),
                        "adminEmail", adminEmail == null ? "" : adminEmail,
                        "appName", app,
                        "appDisplayName", displayNameOr(r.appDisplayName())),
                Map.of());

        return dispatch(r.email(), displayNameOr(r.appDisplayName()),
                app + " Account Registration Status", html, null);
    }

    private SendEmailResult sendRegistrationPending(RegistrationPending r) {
        String app = appNameOr(r.appName());

        String html = emailTemplateService.processTemplate("registration-pending.html",
                Map.of(
                        "userName", r.name(),
                        "adminEmail", adminEmail == null ? "" : adminEmail,
                        "appName", app,
                        "appDisplayName", displayNameOr(r.appDisplayName())),
                Map.of());

        return dispatch(r.email(), displayNameOr(r.appDisplayName()),
                app + " Registration Received - Pending Approval", html, null);
    }

    private SendEmailResult sendPasswordReset(PasswordReset r) {
        String app = appNameOr(r.appName());

        String html = emailTemplateService.processTemplate("password-reset.html",
                Map.of(
                        "userName", StringUtils.hasText(r.name()) ? r.name() : "User",
                        "expiryTime", StringUtils.hasText(r.expiryTime()) ? r.expiryTime() : "24 hours",
                        "adminEmail", adminEmail == null ? "" : adminEmail,
                        "appName", app,
                        "appDisplayName", displayNameOr(r.appDisplayName())),
                Map.of("resetUrl", r.resetUrl()));

        return dispatch(r.email(), app + " Security", "Password Reset Request - " + app, html, null);
    }

    private SendEmailResult sendConsultationConfirmation(ConsultationConfirmation r) {
        ConsultationData data = toConsultationData(r);

        String html = emailTemplateService.processTemplate("consultation-confirmation.html",
                Map.of(
                        "firstName", r.firstName(),
                        "lastName", r.lastName(),
                        "fullName", data.getFullName(),
                        "company", r.company(),
                        "consultationType", r.consultationType(),
                        "formattedDate", data.getFormattedDate(),
                        "formattedTime", data.getFormattedTime(),
                        "notes", nullToEmpty(r.notes())),
                Map.of("meetingLink", r.meetingLink()));

        return dispatch(r.email(), displayNameOr(null),
                "Consultation Confirmed - " + appNameOr(null), html, data.generateCalendarEvent());
    }

    private SendEmailResult sendConsultationNotification(ConsultationNotification r) {
        ConsultationData data = toConsultationData(r);

        Map<String, String> text = new HashMap<>();
        text.put("firstName", r.firstName());
        text.put("lastName", r.lastName());
        text.put("fullName", data.getFullName());
        text.put("email", r.email());
        text.put("company", r.company());
        text.put("consultationType", r.consultationType());
        text.put("formattedDate", data.getFormattedDate());
        text.put("formattedTime", data.getFormattedTime());
        text.put("phone", nullToEmpty(r.phone()));
        text.put("notes", nullToEmpty(r.notes()));

        String html = emailTemplateService.processTemplate("consultation-notification.html",
                text, Map.of("meetingLink", r.meetingLink()));

        // Previously the admin address was hardcoded here, bypassing app.admin-email entirely.
        return dispatch(requireAdminEmail(), displayNameOr(null),
                "New Consultation Scheduled - " + r.company(), html, null);
    }

    // -- sending --------------------------------------------------------------------------------

    /**
     * Composes and sends one message. The single place this service touches SMTP.
     *
     * @param icsContent optional iCalendar payload attached as {@code consultation.ics}
     */
    private SendEmailResult dispatch(String to, String fromDisplayName, String subject,
                                     String htmlContent, String icsContent) {
        String messageId = null;
        try {
            MimeMessage mimeMessage = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, true, "UTF-8");

            helper.setFrom(fromAddress(), fromDisplayName);
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText("", htmlContent);

            if (StringUtils.hasText(icsContent)) {
                helper.addAttachment("consultation.ics",
                        new ByteArrayResource(icsContent.getBytes(StandardCharsets.UTF_8)),
                        "text/calendar");
            }

            // Assign the Message-ID before transmitting, so a send that times out still leaves an
            // identifier to search the mail server's logs for. See SpringMailService.
            mimeMessage.saveChanges();
            messageId = readMessageId(mimeMessage);

            mailSender.send(mimeMessage);

            log.info("Templated email sent: subject='{}' messageId={}", subject, messageId);
            return SendEmailResult.single(messageId, SpringMailService.PROVIDER_NAME);

        } catch (MailException | MessagingException | UnsupportedEncodingException e) {
            log.warn("Templated email failed: subject='{}' messageId={} detail={}",
                    subject, messageId, e.getMessage());
            throw SmtpFailures.translate(e, SpringMailService.PROVIDER_NAME,
                    "the notification email could not be delivered");
        }
    }

    private String readMessageId(MimeMessage mimeMessage) {
        try {
            return mimeMessage.getMessageID();
        } catch (MessagingException e) {
            log.debug("Could not read Message-ID from a sent message", e);
            return null;
        }
    }

    // -- helpers --------------------------------------------------------------------------------

    private static ConsultationData toConsultationData(ConsultationConfirmation r) {
        return new ConsultationData(r.firstName(), r.lastName(), r.email(), r.company(),
                r.consultationType(), r.date(), r.timeSlot(), r.meetingLink(), r.phone(), r.notes());
    }

    private static ConsultationData toConsultationData(ConsultationNotification r) {
        return new ConsultationData(r.firstName(), r.lastName(), r.email(), r.company(),
                r.consultationType(), r.date(), r.timeSlot(), r.meetingLink(), r.phone(), r.notes());
    }

    private String fromAddress() {
        String address = emailProperties.getDefaultFromAddress();
        if (!StringUtils.hasText(address)) {
            throw new IllegalStateException(
                    "app.email.default-from-address is not configured; refusing to send without a sender");
        }
        return address;
    }

    /**
     * The administrator address, from configuration.
     *
     * <p>Fails loudly rather than falling back to a literal. An approval request delivered to the
     * wrong inbox, or to nobody, is a silent workflow break.
     */
    private String requireAdminEmail() {
        if (!StringUtils.hasText(adminEmail)) {
            throw new IllegalStateException(
                    "app.admin-email is not configured; cannot send administrator notifications");
        }
        return adminEmail;
    }

    private String appNameOr(String supplied) {
        return StringUtils.hasText(supplied) ? supplied : appName;
    }

    private String displayNameOr(String supplied) {
        return StringUtils.hasText(supplied) ? supplied : appDisplayName;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
