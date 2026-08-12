package com.hoseacodes.emailintegrator.controller;

import com.hoseacodes.emailintegrator.email.EmailProviderException;
import com.hoseacodes.emailintegrator.email.EmailProviderException.Reason;
import com.hoseacodes.emailintegrator.email.SendEmailResult;
import com.hoseacodes.emailintegrator.service.EmailDeliveryService;
import com.hoseacodes.emailintegrator.service.EmailDraft;
import com.hoseacodes.emailintegrator.service.EmailSendingDisabledException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP-layer tests for {@code POST /email}.
 *
 * <p>The service is mocked because what is under test here is the HTTP contract, not delivery:
 * status codes, the shape of the error body, which inputs are rejected before any provider is
 * contacted, and — importantly — what the API does <em>not</em> disclose when things fail.
 *
 * <p>Provider behaviour is covered separately by {@code BrevoEmailProviderTest} against a real
 * HTTP server. Splitting them this way keeps each test honest about what it proves.
 *
 * <p><b>On {@code addFilters = false}.</b> The security filter chain is bypassed here so these
 * tests exercise controller and error-handling behaviour without every case needing a valid
 * credential. That is only acceptable because the security rules are asserted in full, against
 * the real filter chain, in {@code ApiKeySecurityTest} — including that this very endpoint
 * returns 401 without a key. Disabling filters to make tests pass, with nothing else covering
 * them, would be how authentication quietly stops working.
 */
@WebMvcTest(EmailController.class)
@AutoConfigureMockMvc(addFilters = false)
class EmailControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private EmailDeliveryService emailDeliveryService;

    private static final String VALID_REQUEST = """
            {
              "to": [{"email": "recipient@example.com", "name": "Recipient"}],
              "subject": "Quarterly update",
              "htmlContent": "<p>Hello</p>"
            }
            """;

    // -- success -------------------------------------------------------------------------------

    @Nested
    @DisplayName("successful requests")
    class Success {

        @Test
        @DisplayName("returns 202 Accepted with the provider message ids")
        void acceptsValidRequest() throws Exception {
            given(emailDeliveryService.send(any()))
                    .willReturn(SendEmailResult.single("<abc@brevo>", "brevo"));

            mockMvc.perform(post("/email")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(VALID_REQUEST))
                    // 202, not 200: the provider accepted the message for processing. Actual
                    // mailbox delivery happens later and can still bounce.
                    .andExpect(status().isAccepted())
                    .andExpect(jsonPath("$.messageIds[0]").value("<abc@brevo>"))
                    .andExpect(jsonPath("$.provider").value("brevo"));
        }

        @Test
        @DisplayName("passes recipients through to the service without a caller-supplied sender")
        void doesNotAcceptSenderFromCaller() throws Exception {
            given(emailDeliveryService.send(any()))
                    .willReturn(SendEmailResult.single("<abc@brevo>", "brevo"));

            // A caller trying to set their own From address: the field is not part of the
            // contract, so Jackson ignores it and the configured sender is used regardless.
            mockMvc.perform(post("/email")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "from": "ceo@victim.example",
                                      "to": [{"email": "recipient@example.com"}],
                                      "subject": "Urgent wire transfer",
                                      "textContent": "please pay this invoice"
                                    }
                                    """))
                    .andExpect(status().isAccepted());

            ArgumentCaptor<EmailDraft> captor = ArgumentCaptor.forClass(EmailDraft.class);
            org.mockito.Mockito.verify(emailDeliveryService).send(captor.capture());

            // EmailDraft has no sender field at all — the spoofing attempt cannot even be expressed.
            assertThat(captor.getValue().to()).hasSize(1);
            assertThat(captor.getValue().to().get(0).email()).isEqualTo("recipient@example.com");
        }
    }

    // -- validation ----------------------------------------------------------------------------

    @Nested
    @DisplayName("request validation")
    class Validation {

        @Test
        @DisplayName("rejects a request with no recipients and never calls the provider")
        void rejectsMissingRecipients() throws Exception {
            mockMvc.perform(post("/email")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"subject": "No one to send to", "textContent": "hello"}
                                    """))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.validationErrors[?(@.field == 'to')]").exists());

            // Invalid input must cost nothing downstream — no provider call, no quota consumed.
            verifyNoInteractions(emailDeliveryService);
        }

        @Test
        @DisplayName("rejects a malformed email address and names the offending field")
        void rejectsInvalidEmail() throws Exception {
            mockMvc.perform(post("/email")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "to": [{"email": "not-an-email-address"}],
                                      "subject": "Test",
                                      "textContent": "hello"
                                    }
                                    """))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                    // The indexed path tells a caller exactly which recipient is wrong.
                    .andExpect(jsonPath("$.validationErrors[?(@.field == 'to[0].email')]").exists());

            verifyNoInteractions(emailDeliveryService);
        }

        @Test
        @DisplayName("rejects a blank subject")
        void rejectsBlankSubject() throws Exception {
            mockMvc.perform(post("/email")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "to": [{"email": "recipient@example.com"}],
                                      "subject": "   ",
                                      "textContent": "hello"
                                    }
                                    """))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.validationErrors[?(@.field == 'subject')]").exists());
        }

        @Test
        @DisplayName("rejects a message with neither html nor text content")
        void rejectsEmptyBody() throws Exception {
            mockMvc.perform(post("/email")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "to": [{"email": "recipient@example.com"}],
                                      "subject": "Empty"
                                    }
                                    """))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                    // The cross-field rule reports through the same channel as single-field rules.
                    .andExpect(jsonPath("$.validationErrors[?(@.field == 'bodyPresent')]").exists());

            verifyNoInteractions(emailDeliveryService);
        }

        @Test
        @DisplayName("rejects an over-long recipient list rather than fanning out unbounded work")
        void rejectsTooManyRecipients() throws Exception {
            String recipients = java.util.stream.IntStream.range(0, 51)
                    .mapToObj(i -> "{\"email\":\"user%d@example.com\"}".formatted(i))
                    .collect(java.util.stream.Collectors.joining(","));

            mockMvc.perform(post("/email")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"to": [%s], "subject": "Bulk", "textContent": "hi"}
                                    """.formatted(recipients)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

            verifyNoInteractions(emailDeliveryService);
        }

        @Test
        @DisplayName("malformed JSON yields a clean 400, not a stack trace")
        void rejectsMalformedJson() throws Exception {
            mockMvc.perform(post("/email")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"to\": [{\"email\": "))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                    .andExpect(jsonPath("$.errorId").exists())
                    // Jackson's parser message can quote the payload; it must not be echoed back.
                    .andExpect(jsonPath("$.message").value("Request body could not be parsed as JSON"));
        }
    }

    // -- provider failures mapped to status codes ---------------------------------------------

    @Nested
    @DisplayName("provider failures")
    class ProviderFailures {

        @Test
        @DisplayName("provider auth failure is 502, never 401 — the caller's credentials were fine")
        void providerAuthFailureIsBadGateway() throws Exception {
            willThrow(new EmailProviderException(Reason.PROVIDER_AUTH_FAILED, "brevo",
                    "Brevo rejected our API credentials (HTTP 401)"))
                    .given(emailDeliveryService).send(any());

            mockMvc.perform(post("/email")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(VALID_REQUEST))
                    .andExpect(status().isBadGateway())
                    .andExpect(jsonPath("$.code").value("PROVIDER_PROVIDER_AUTH_FAILED"))
                    // Our provider's name and our key troubles are not the caller's business.
                    .andExpect(jsonPath("$.message").value(
                            "The email service is misconfigured and could not authenticate with its provider."));
        }

        @Test
        @DisplayName("rate limiting is 429 and forwards Retry-After so the caller can back off")
        void rateLimitIsForwarded() throws Exception {
            willThrow(new EmailProviderException(Reason.RATE_LIMITED, "brevo",
                    "rate limited", Duration.ofSeconds(30), null))
                    .given(emailDeliveryService).send(any());

            mockMvc.perform(post("/email")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(VALID_REQUEST))
                    .andExpect(status().isTooManyRequests())
                    .andExpect(header().string("Retry-After", "30"));
        }

        @Test
        @DisplayName("a timeout is 504 and warns the caller that delivery is uncertain")
        void timeoutSignalsDeliveryUncertainty() throws Exception {
            willThrow(new EmailProviderException(Reason.TIMEOUT, "brevo", "no response in time"))
                    .given(emailDeliveryService).send(any());

            mockMvc.perform(post("/email")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(VALID_REQUEST))
                    .andExpect(status().isGatewayTimeout())
                    // This flag is the whole point: it tells the caller a blind retry may send
                    // the recipient a second copy.
                    .andExpect(jsonPath("$.deliveryUncertain").value(true));
        }

        @Test
        @DisplayName("an unreachable provider is 503 and states plainly that nothing was sent")
        void connectFailureIsServiceUnavailable() throws Exception {
            willThrow(new EmailProviderException(Reason.CONNECT_FAILED, "brevo", "could not connect"))
                    .given(emailDeliveryService).send(any());

            mockMvc.perform(post("/email")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(VALID_REQUEST))
                    .andExpect(status().isServiceUnavailable())
                    // Not side-effect-possible, so the field is omitted entirely.
                    .andExpect(jsonPath("$.deliveryUncertain").doesNotExist());
        }

        @Test
        @DisplayName("the configuration kill switch yields 503, not 500")
        void sendingDisabled() throws Exception {
            willThrow(new EmailSendingDisabledException()).given(emailDeliveryService).send(any());

            mockMvc.perform(post("/email")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(VALID_REQUEST))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value("EMAIL_SENDING_DISABLED"));
        }
    }

    // -- information disclosure ----------------------------------------------------------------

    @Test
    @DisplayName("an unexpected failure returns a correlation id and nothing else")
    void unexpectedFailureDisclosesNothing() throws Exception {
        willThrow(new IllegalStateException(
                "smtp-relay.brevo.com:587 refused credentials for account 41288"))
                .given(emailDeliveryService).send(any());

        mockMvc.perform(post("/email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_REQUEST))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.errorId").exists())
                // Hostnames, ports, and account identifiers stay in the server log.
                .andExpect(jsonPath("$.message").value(
                        "An unexpected error occurred. Quote errorId when reporting this."));
    }

    @Test
    @DisplayName("every error response carries the same contract fields")
    void errorContractIsConsistent() throws Exception {
        willThrow(new EmailProviderException(Reason.PROVIDER_UNAVAILABLE, "brevo", "boom"))
                .given(emailDeliveryService).send(any());

        mockMvc.perform(post("/email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_REQUEST))
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.status").value(502))
                .andExpect(jsonPath("$.code").exists())
                .andExpect(jsonPath("$.message").exists())
                .andExpect(jsonPath("$.path").value("/email"))
                .andExpect(jsonPath("$.errorId").exists());
    }

    @Test
    @DisplayName("no error response ever contains a stack trace")
    void noStackTracesLeak() throws Exception {
        willThrow(new RuntimeException("internal failure", new IllegalStateException("root cause")))
                .given(emailDeliveryService).send(any());

        String body = mockMvc.perform(post("/email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_REQUEST))
                .andExpect(status().isInternalServerError())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("com.hoseacodes");
        assertThat(body).doesNotContain("java.lang");
        assertThat(body).doesNotContain("at ");
        assertThat(body).doesNotContain("root cause");
    }

    @Test
    @DisplayName("unsupported media type is rejected before parsing")
    void rejectsNonJsonContentType() throws Exception {
        mockMvc.perform(post("/email")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("to=someone@example.com"))
                .andExpect(status().isUnsupportedMediaType());

        verifyNoInteractions(emailDeliveryService);
    }
}
