package com.hoseacodes.emailintegrator.service;

import com.hoseacodes.emailintegrator.config.EmailProperties;
import com.hoseacodes.emailintegrator.email.EmailAddress;
import com.hoseacodes.emailintegrator.email.EmailProvider;
import com.hoseacodes.emailintegrator.email.SendEmailCommand;
import com.hoseacodes.emailintegrator.email.SendEmailResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Unit tests for the application-layer send.
 *
 * <p>The provider is mocked here because the point of this class is not delivery — it is the
 * handful of decisions the application owns: where the sender comes from, whether the kill
 * switch is honoured, and that neither can be bypassed by a caller.
 */
class EmailDeliveryServiceTest {

    private EmailProvider provider;
    private EmailProperties properties;
    private EmailDeliveryService service;

    @BeforeEach
    void setUp() {
        provider = mock(EmailProvider.class);
        given(provider.name()).willReturn("brevo");

        properties = new EmailProperties();
        properties.setEnabled(true);
        properties.setDefaultFromAddress("noreply@ambitiousconcept.com");
        properties.setDefaultFromName("Ambitious Concept");

        service = new EmailDeliveryService(provider, properties);
    }

    private static EmailDraft draft() {
        return new EmailDraft(
                List.of(new EmailAddress("recipient@example.com", "Recipient")),
                List.of(), List.of(), null,
                "Subject", "<p>Body</p>", null, List.of());
    }

    @Test
    @DisplayName("the sender always comes from configuration, never from the request")
    void senderComesFromConfiguration() {
        given(provider.send(any())).willReturn(SendEmailResult.single("<id@brevo>", "brevo"));

        service.send(draft());

        ArgumentCaptor<SendEmailCommand> captor = ArgumentCaptor.forClass(SendEmailCommand.class);
        verify(provider).send(captor.capture());

        assertThat(captor.getValue().sender().email()).isEqualTo("noreply@ambitiousconcept.com");
        assertThat(captor.getValue().sender().name()).isEqualTo("Ambitious Concept");
    }

    @Test
    @DisplayName("passes the caller's recipients and content through unchanged")
    void passesDraftThrough() {
        given(provider.send(any())).willReturn(SendEmailResult.single("<id@brevo>", "brevo"));

        service.send(draft());

        ArgumentCaptor<SendEmailCommand> captor = ArgumentCaptor.forClass(SendEmailCommand.class);
        verify(provider).send(captor.capture());

        SendEmailCommand command = captor.getValue();
        assertThat(command.to()).extracting(EmailAddress::email).containsExactly("recipient@example.com");
        assertThat(command.subject()).isEqualTo("Subject");
        assertThat(command.htmlContent()).isEqualTo("<p>Body</p>");
    }

    @Test
    @DisplayName("returns the provider's message ids to the caller")
    void returnsProviderResult() {
        given(provider.send(any())).willReturn(new SendEmailResult(List.of("<a@brevo>", "<b@brevo>"), "brevo"));

        SendEmailResult result = service.send(draft());

        assertThat(result.messageIds()).containsExactly("<a@brevo>", "<b@brevo>");
        assertThat(result.provider()).isEqualTo("brevo");
    }

    @Test
    @DisplayName("the kill switch prevents the provider from being contacted at all")
    void disabledSendingShortCircuits() {
        properties.setEnabled(false);

        assertThatThrownBy(() -> service.send(draft()))
                .isInstanceOf(EmailSendingDisabledException.class);

        // The check must happen before the provider call, not after — otherwise "disabled"
        // would still consume provider quota and still deliver mail.
        verifyNoInteractions(provider);
    }

    @Test
    @DisplayName("refuses to send when no sender address is configured")
    void missingSenderIsRefused() {
        properties.setDefaultFromAddress("   ");

        assertThatThrownBy(() -> service.send(draft()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("default-from-address");

        // Better to fail loudly than to let the provider substitute some default identity.
        verifyNoInteractions(provider);
    }

    @Test
    @DisplayName("provider failures propagate unchanged for the exception handler to translate")
    void providerFailuresPropagate() {
        given(provider.send(any())).willThrow(new com.hoseacodes.emailintegrator.email.EmailProviderException(
                com.hoseacodes.emailintegrator.email.EmailProviderException.Reason.TIMEOUT, "brevo", "timed out"));

        // The service deliberately does not catch, wrap, or swallow. Converting a failure into a
        // boolean or a null here would discard the side-effect-possible signal the caller needs.
        assertThatThrownBy(() -> service.send(draft()))
                .isInstanceOf(com.hoseacodes.emailintegrator.email.EmailProviderException.class);
    }
}
