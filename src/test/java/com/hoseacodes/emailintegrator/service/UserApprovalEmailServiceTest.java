package com.hoseacodes.emailintegrator.service;

import com.hoseacodes.emailintegrator.config.EmailProperties;
import com.hoseacodes.emailintegrator.controller.dto.TemplatedEmailRequest;
import com.hoseacodes.emailintegrator.email.EmailProviderException;
import com.hoseacodes.emailintegrator.email.EmailProviderException.Reason;
import com.hoseacodes.emailintegrator.email.SendEmailResult;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Tests for templated email dispatch.
 *
 * <p>{@link JavaMailSender} is mocked but produces a real {@link MimeMessage}, so the assertions
 * inspect the message actually composed — recipient, subject, attachments — rather than only that
 * a method was called.
 */
class UserApprovalEmailServiceTest {

    private static final String ADMIN = "admin@ambitiousconcept.com";

    private JavaMailSender mailSender;
    private EmailProperties properties;
    private UserApprovalEmailService service;

    @BeforeEach
    void setUp() {
        mailSender = mock(JavaMailSender.class);
        given(mailSender.createMimeMessage())
                .willAnswer(invocation -> new MimeMessage(Session.getInstance(new Properties())));

        properties = new EmailProperties();
        properties.setEnabled(true);
        properties.setDefaultFromAddress("noreply@ambitiousconcept.com");
        properties.setDefaultFromName("Ambitious Concept");

        service = new UserApprovalEmailService(
                mailSender, properties, new EmailTemplateService(new LinkSanitizer(properties)));

        // @Value fields; set directly rather than booting a context for a unit test.
        ReflectionTestUtils.setField(service, "baseUrl", "https://app.example.com");
        ReflectionTestUtils.setField(service, "adminEmail", ADMIN);
        ReflectionTestUtils.setField(service, "appName", "Email Integrator");
        ReflectionTestUtils.setField(service, "appDisplayName", "Email Integrator Service");
    }

    /** Every permitted subtype, so the coverage test below cannot silently miss one. */
    private static List<TemplatedEmailRequest> allRequestTypes() {
        return List.of(
                new TemplatedEmailRequest.ApprovalRequest("user@example.com", "Alex", null, null,
                        "https://app.example.com/approve", "https://app.example.com/deny"),
                new TemplatedEmailRequest.AccountApproved("user@example.com", "Alex", null, null, null),
                new TemplatedEmailRequest.AccountDenied("user@example.com", "Alex", null, null),
                new TemplatedEmailRequest.RegistrationPending("user@example.com", "Alex", null, null),
                new TemplatedEmailRequest.PasswordReset("user@example.com", "Alex",
                        "https://app.example.com/reset?t=abc", null, null, null),
                new TemplatedEmailRequest.ConsultationConfirmation("Alex", "Smith", "user@example.com",
                        "Acme", "Architecture review", "2026-09-01", "14:00",
                        "https://meet.example.com/abc", null, null),
                new TemplatedEmailRequest.ConsultationNotification("Alex", "Smith", "user@example.com",
                        "Acme", "Architecture review", "2026-09-01", "14:00",
                        "https://meet.example.com/abc", null, null));
    }

    // -- dispatch coverage ---------------------------------------------------------------------

    @Test
    @DisplayName("every permitted request type has a handler")
    void everySubtypeIsHandled() {
        // Stands in for the compile-time exhaustiveness check a pattern-matching switch would
        // give on Java 21. If a subtype is added to the sealed interface and not handled, send()
        // throws IllegalStateException and this fails.
        for (TemplatedEmailRequest request : allRequestTypes()) {
            assertThatCode(() -> service.send(request))
                    .as("handler for templateType '%s'", request.templateType())
                    .doesNotThrowAnyException();
        }
    }

    @Test
    @DisplayName("the test above covers every subtype the sealed interface permits")
    void coverageListIsComplete() {
        // Guards the guard: if a new record is permitted but not added to allRequestTypes(), the
        // coverage test would pass while testing nothing about it.
        int permitted = TemplatedEmailRequest.class.getPermittedSubclasses().length;

        assertThat(allRequestTypes())
                .as("allRequestTypes() must include every permitted subtype")
                .hasSize(permitted);
    }

    // -- addressing ------------------------------------------------------------------------------

    @Test
    @DisplayName("an approval request goes to the configured admin, not to the registering user")
    void approvalRequestGoesToAdmin() throws Exception {
        service.send(new TemplatedEmailRequest.ApprovalRequest(
                "newuser@example.com", "New User", null, null,
                "https://app.example.com/approve", "https://app.example.com/deny"));

        assertThat(sentMessage().getAllRecipients()[0].toString()).contains(ADMIN);
    }

    @Test
    @DisplayName("a consultation notification goes to the configured admin, not a hardcoded address")
    void consultationNotificationGoesToConfiguredAdmin() throws Exception {
        service.send(new TemplatedEmailRequest.ConsultationNotification(
                "Alex", "Smith", "client@example.com", "Acme", "Architecture review",
                "2026-09-01", "14:00", "https://meet.example.com/abc", null, null));

        // This address was previously hardcoded in the service, bypassing app.admin-email.
        assertThat(sentMessage().getAllRecipients()[0].toString()).contains(ADMIN);
    }

    @Test
    @DisplayName("user-facing messages go to the user")
    void userMessagesGoToUser() throws Exception {
        service.send(new TemplatedEmailRequest.AccountApproved(
                "user@example.com", "Alex", null, null, null));

        assertThat(sentMessage().getAllRecipients()[0].toString()).contains("user@example.com");
    }

    @Test
    @DisplayName("subjects use the configured application name, not stale hardcoded branding")
    void subjectsUseConfiguredAppName() throws Exception {
        service.send(new TemplatedEmailRequest.AccountApproved(
                "user@example.com", "Alex", null, null, null));

        // Subjects previously read "Your Storm Gate Account Has Been Approved" — branding left
        // over from a different project.
        assertThat(sentMessage().getSubject())
                .contains("Email Integrator")
                .doesNotContain("Storm Gate");
    }

    @Test
    @DisplayName("a per-request app name overrides the configured default")
    void requestAppNameOverridesConfiguration() throws Exception {
        service.send(new TemplatedEmailRequest.AccountApproved(
                "user@example.com", "Alex", "Acme Portal", "Acme Portal Suite", null));

        assertThat(sentMessage().getSubject()).contains("Acme Portal");
    }

    @Test
    @DisplayName("a consultation confirmation carries the calendar attachment")
    void consultationConfirmationHasIcsAttachment() throws Exception {
        service.send(new TemplatedEmailRequest.ConsultationConfirmation(
                "Alex", "Smith", "client@example.com", "Acme", "Architecture review",
                "2026-09-01", "14:00", "https://meet.example.com/abc", null, "Bring the diagrams"));

        assertThat(attachmentNames(sentMessage())).contains("consultation.ics");
    }

    @Test
    @DisplayName("other templates carry no attachment")
    void otherTemplatesHaveNoAttachment() throws Exception {
        service.send(new TemplatedEmailRequest.AccountApproved(
                "user@example.com", "Alex", null, null, null));

        assertThat(attachmentNames(sentMessage())).isEmpty();
    }

    /**
     * Collects attachment file names by walking the MIME tree.
     *
     * <p>{@code getContent()} returns a {@code MimeMultipart} object, so inspecting its
     * {@code toString()} tells you nothing about what is attached — the parts have to be walked.
     */
    private static List<String> attachmentNames(MimeMessage message) throws Exception {
        List<String> names = new java.util.ArrayList<>();
        collectAttachmentNames(message.getContent(), names);
        return names;
    }

    private static void collectAttachmentNames(Object content, List<String> names) throws Exception {
        if (!(content instanceof jakarta.mail.Multipart multipart)) {
            return;
        }
        for (int i = 0; i < multipart.getCount(); i++) {
            jakarta.mail.BodyPart part = multipart.getBodyPart(i);
            if (part.getFileName() != null) {
                names.add(part.getFileName());
            }
            // Bodies are themselves nested multiparts (mixed → related → alternative).
            collectAttachmentNames(part.getContent(), names);
        }
    }

    // -- failure handling ------------------------------------------------------------------------

    @Test
    @DisplayName("a delivery failure throws rather than reporting success with a false flag")
    void deliveryFailureThrows() {
        willThrow(new MailSendException("connection reset"))
                .given(mailSender).send(any(MimeMessage.class));

        EmailProviderException e = catchThrowableOfType(
                () -> service.send(new TemplatedEmailRequest.AccountApproved(
                        "user@example.com", "Alex", null, null, null)),
                EmailProviderException.class);

        // Previously this returned false and the controller reported HTTP 200 with
        // "emailSent": false, so a caller saw success for a message that never arrived.
        assertThat(e.getReason()).isEqualTo(Reason.PROVIDER_UNAVAILABLE);
        assertThat(e.isSideEffectPossible()).isTrue();
    }

    @Test
    @DisplayName("SMTP failures are classified identically to the direct send path")
    void classificationMatchesDirectPath() {
        willThrow(new MailAuthenticationException("535 auth failed"))
                .given(mailSender).send(any(MimeMessage.class));

        EmailProviderException e = catchThrowableOfType(
                () -> service.send(new TemplatedEmailRequest.AccountDenied(
                        "user@example.com", "Alex", null, null)),
                EmailProviderException.class);

        assertThat(e.getReason()).isEqualTo(Reason.PROVIDER_AUTH_FAILED);
        assertThat(e.getMessage()).doesNotContain("535");
    }

    @Test
    @DisplayName("a dangerous link is refused before anything is sent")
    void dangerousLinkIsRefused() {
        assertThatThrownBy(() -> service.send(new TemplatedEmailRequest.PasswordReset(
                "victim@example.com", "Alex", "javascript:alert(1)", null, null, null)))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(mailSender);
    }

    @Test
    @DisplayName("the kill switch prevents any message being composed")
    void disabledSendingShortCircuits() {
        properties.setEnabled(false);

        assertThatThrownBy(() -> service.send(new TemplatedEmailRequest.AccountApproved(
                "user@example.com", "Alex", null, null, null)))
                .isInstanceOf(EmailSendingDisabledException.class);

        verifyNoInteractions(mailSender);
    }

    @Test
    @DisplayName("refuses to send admin notifications when no admin address is configured")
    void missingAdminEmailIsRefused() {
        ReflectionTestUtils.setField(service, "adminEmail", "");

        // Failing loudly beats delivering an approval request to nobody.
        assertThatThrownBy(() -> service.send(new TemplatedEmailRequest.ApprovalRequest(
                "user@example.com", "Alex", null, null,
                "https://app.example.com/approve", "https://app.example.com/deny")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.admin-email");
    }

    @Test
    @DisplayName("a successful send returns the real Message-ID")
    void returnsMessageId() {
        SendEmailResult result = service.send(new TemplatedEmailRequest.AccountApproved(
                "user@example.com", "Alex", null, null, null));

        assertThat(result.provider()).isEqualTo("gmail-smtp");
        assertThat(result.messageIds()).hasSize(1);
        assertThat(result.messageIds().get(0)).contains("@");
    }

    private MimeMessage sentMessage() {
        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(captor.capture());
        return captor.getValue();
    }
}
