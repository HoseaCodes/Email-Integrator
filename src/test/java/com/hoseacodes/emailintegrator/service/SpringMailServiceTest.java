package com.hoseacodes.emailintegrator.service;

import com.hoseacodes.emailintegrator.config.EmailProperties;
import com.hoseacodes.emailintegrator.controller.dto.Recipient;
import com.hoseacodes.emailintegrator.controller.dto.SendMailRequest;
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

import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Tests for the SMTP send path.
 *
 * <p>{@link JavaMailSender} is mocked, but the {@link MimeMessage} it returns is real, so the
 * assertions below check the message this service actually composes — headers included — rather
 * than merely that a method was called.
 */
class SpringMailServiceTest {

    private JavaMailSender mailSender;
    private EmailProperties properties;
    private SpringMailService service;

    @BeforeEach
    void setUp() {
        mailSender = mock(JavaMailSender.class);
        // A real MimeMessage backed by a bare Session: no network, but genuine header handling.
        given(mailSender.createMimeMessage())
                .willAnswer(invocation -> new MimeMessage(Session.getInstance(new Properties())));

        properties = new EmailProperties();
        properties.setEnabled(true);
        properties.setDefaultFromAddress("noreply@ambitiousconcept.com");
        properties.setDefaultFromName("Ambitious Concept");

        service = new SpringMailService(mailSender, properties);
    }

    private static SendMailRequest request() {
        return new SendMailRequest(
                List.of(new Recipient("recipient@example.com", "Recipient")),
                null, null, null,
                "Subject line", "<p>Hello</p>", "Hello");
    }

    // -- composition ---------------------------------------------------------------------------

    @Test
    @DisplayName("the From header comes from configuration, not from the caller")
    void senderComesFromConfiguration() throws Exception {
        service.send(request());

        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(captor.capture());

        assertThat(captor.getValue().getFrom()[0].toString())
                .contains("noreply@ambitiousconcept.com")
                .contains("Ambitious Concept");
    }

    @Test
    @DisplayName("recipients, subject, cc and bcc are set on the message")
    void composesMessage() throws Exception {
        service.send(new SendMailRequest(
                List.of(new Recipient("to@example.com", "To")),
                List.of(new Recipient("cc@example.com", null)),
                List.of(new Recipient("bcc@example.com", null)),
                new Recipient("reply@example.com", null),
                "Quarterly update", "<p>Body</p>", "Body"));

        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(captor.capture());
        MimeMessage sent = captor.getValue();

        assertThat(sent.getSubject()).isEqualTo("Quarterly update");
        assertThat(sent.getRecipients(MimeMessage.RecipientType.TO)[0].toString())
                .contains("to@example.com");
        assertThat(sent.getRecipients(MimeMessage.RecipientType.CC)[0].toString())
                .contains("cc@example.com");
        assertThat(sent.getRecipients(MimeMessage.RecipientType.BCC)[0].toString())
                .contains("bcc@example.com");
        assertThat(sent.getReplyTo()[0].toString()).contains("reply@example.com");
    }

    @Test
    @DisplayName("the returned id is the real SMTP Message-ID, not an invented one")
    void returnsRealMessageId() {
        SendEmailResult result = service.send(request());

        assertThat(result.provider()).isEqualTo("gmail-smtp");
        // The previous implementation returned a fresh UUID that corresponded to nothing, which is
        // worse than no id because it looks traceable. This is the actual Message-ID header.
        assertThat(result.messageIds()).hasSize(1);
        assertThat(result.messageIds().get(0)).contains("@");
    }

    @Test
    @DisplayName("the Message-ID is assigned before transmission, so a failed send still has one")
    void messageIdIsAssignedBeforeSending() throws Exception {
        // The case this protects: a send that times out. Delivery is genuinely unknown, and the
        // Message-ID is the only handle for checking the mail server's logs afterwards. Reading it
        // back after a failure would yield nothing.
        willThrow(new MailSendException("timed out")).given(mailSender).send(any(MimeMessage.class));

        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        catchThrowableOfType(() -> service.send(request()), EmailProviderException.class);

        verify(mailSender).send(captor.capture());
        assertThat(captor.getValue().getMessageID()).isNotNull().contains("@");
    }

    // -- failure translation -------------------------------------------------------------------

    @Test
    @DisplayName("SMTP authentication failure is our misconfiguration, and cannot have sent")
    void authenticationFailure() {
        willThrow(new MailAuthenticationException("535 auth failed for user@host"))
                .given(mailSender).send(any(MimeMessage.class));

        EmailProviderException e = catchThrowableOfType(
                () -> service.send(request()), EmailProviderException.class);

        assertThat(e.getReason()).isEqualTo(Reason.PROVIDER_AUTH_FAILED);
        assertThat(e.isSideEffectPossible()).isFalse();
        assertThat(e.getProvider()).isEqualTo("gmail-smtp");
    }

    @Test
    @DisplayName("a delivery failure is treated as possibly-sent, because SMTP is not atomic")
    void sendFailureIsSideEffectPossible() {
        willThrow(new MailSendException("failed to deliver to some recipients"))
                .given(mailSender).send(any(MimeMessage.class));

        EmailProviderException e = catchThrowableOfType(
                () -> service.send(request()), EmailProviderException.class);

        assertThat(e.getReason()).isEqualTo(Reason.PROVIDER_UNAVAILABLE);
        // A MailSendException can be raised after the server accepted the message for some
        // recipients. Calling this "definitely not sent" would license a duplicating retry.
        assertThat(e.isSideEffectPossible()).isTrue();
    }

    @Test
    @DisplayName("the SMTP server's response text is not copied into the exception message")
    void doesNotLeakServerResponse() {
        willThrow(new MailAuthenticationException(
                "535-5.7.8 Username and Password not accepted for account 41288 at smtp.gmail.com:587"))
                .given(mailSender).send(any(MimeMessage.class));

        EmailProviderException e = catchThrowableOfType(
                () -> service.send(request()), EmailProviderException.class);

        // Hostnames, ports, and account identifiers stay in the log, not in anything a caller sees.
        assertThat(e.getMessage()).doesNotContain("smtp.gmail.com");
        assertThat(e.getMessage()).doesNotContain("41288");
        assertThat(e.getMessage()).doesNotContain("535");
    }

    // -- guards --------------------------------------------------------------------------------

    @Test
    @DisplayName("the kill switch prevents any message being composed or sent")
    void disabledSendingShortCircuits() {
        properties.setEnabled(false);

        assertThatThrownBy(() -> service.send(request()))
                .isInstanceOf(EmailSendingDisabledException.class);

        verifyNoInteractions(mailSender);
    }

    @Test
    @DisplayName("refuses to send when no sender address is configured")
    void missingSenderIsRefused() {
        properties.setDefaultFromAddress("  ");

        assertThatThrownBy(() -> service.send(request()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("default-from-address");
    }
}
