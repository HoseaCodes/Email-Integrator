package com.hoseacodes.emailintegrator.controller;

import com.hoseacodes.emailintegrator.controller.dto.TemplatedEmailRequest;
import com.hoseacodes.emailintegrator.email.EmailProviderException;
import com.hoseacodes.emailintegrator.email.EmailProviderException.Reason;
import com.hoseacodes.emailintegrator.email.SendEmailResult;
import com.hoseacodes.emailintegrator.service.ApprovalTokenService;
import com.hoseacodes.emailintegrator.service.UserApprovalEmailService;
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

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP contract for the approval workflow.
 *
 * <p>Filters are disabled so these tests exercise routing, validation, and error translation.
 * The security rules for these same endpoints — including that the {@code POST} routes return 401
 * without a key — are asserted against the real filter chain in {@code ApiKeySecurityTest}.
 */
@WebMvcTest(UserApprovalController.class)
@AutoConfigureMockMvc(addFilters = false)
class UserApprovalControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ApprovalTokenService approvalTokenService;

    @MockBean
    private UserApprovalEmailService userApprovalEmailService;

    private void givenSendSucceeds() {
        given(userApprovalEmailService.send(any()))
                .willReturn(SendEmailResult.single("<id@smtp>", "gmail-smtp"));
    }

    // -- templated email -------------------------------------------------------------------------

    @Nested
    @DisplayName("POST /auth/send-email")
    class SendTemplatedEmail {

        @Test
        @DisplayName("deserialises to the right type and accepts a valid request")
        void acceptsApprovalRequest() throws Exception {
            givenSendSucceeds();

            mockMvc.perform(post("/auth/send-email")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "templateType": "approval",
                                      "email": "user@example.com",
                                      "name": "Alex",
                                      "approvalUrl": "https://app.example.com/approve",
                                      "denyUrl": "https://app.example.com/deny"
                                    }
                                    """))
                    .andExpect(status().isAccepted())
                    .andExpect(jsonPath("$.messageIds[0]").value("<id@smtp>"));

            ArgumentCaptor<TemplatedEmailRequest> captor =
                    ArgumentCaptor.forClass(TemplatedEmailRequest.class);
            verify(userApprovalEmailService).send(captor.capture());

            assertThat(captor.getValue())
                    .isInstanceOf(TemplatedEmailRequest.ApprovalRequest.class);
            assertThat(captor.getValue().templateType()).isEqualTo("approval");
        }

        @Test
        @DisplayName("routes each templateType to its own payload type")
        void routesPasswordReset() throws Exception {
            givenSendSucceeds();

            mockMvc.perform(post("/auth/send-email")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "templateType": "password-reset",
                                      "email": "user@example.com",
                                      "resetUrl": "https://app.example.com/reset?t=abc"
                                    }
                                    """))
                    .andExpect(status().isAccepted());

            ArgumentCaptor<TemplatedEmailRequest> captor =
                    ArgumentCaptor.forClass(TemplatedEmailRequest.class);
            verify(userApprovalEmailService).send(captor.capture());
            assertThat(captor.getValue()).isInstanceOf(TemplatedEmailRequest.PasswordReset.class);
        }

        @Test
        @DisplayName("enforces the required fields for the selected type")
        void validatesPerTypeRequirements() throws Exception {
            // approvalUrl and denyUrl are required for "approval" but meaningless for other types.
            // Previously every field was optional because the body was an untyped Map.
            mockMvc.perform(post("/auth/send-email")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "templateType": "approval",
                                      "email": "user@example.com",
                                      "name": "Alex"
                                    }
                                    """))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.validationErrors[?(@.field == 'approvalUrl')]").exists())
                    .andExpect(jsonPath("$.validationErrors[?(@.field == 'denyUrl')]").exists());

            verifyNoInteractions(userApprovalEmailService);
        }

        @Test
        @DisplayName("rejects a malformed email address")
        void validatesEmailFormat() throws Exception {
            mockMvc.perform(post("/auth/send-email")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "templateType": "denied",
                                      "email": "not-an-email",
                                      "name": "Alex"
                                    }
                                    """))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.validationErrors[?(@.field == 'email')]").exists());

            verifyNoInteractions(userApprovalEmailService);
        }

        @Test
        @DisplayName("an unknown templateType is named as such and lists the valid values")
        void unknownTemplateTypeIsExplained() throws Exception {
            mockMvc.perform(post("/auth/send-email")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"templateType": "not-a-real-template", "email": "user@example.com"}
                                    """))
                    .andExpect(status().isBadRequest())
                    // Not "could not be parsed as JSON" — the document is valid, one value is wrong.
                    .andExpect(jsonPath("$.code").value("UNKNOWN_TEMPLATE_TYPE"))
                    .andExpect(jsonPath("$.message").value(
                            org.hamcrest.Matchers.containsString("password-reset")));

            verifyNoInteractions(userApprovalEmailService);
        }

        @Test
        @DisplayName("a missing templateType is rejected")
        void missingTemplateTypeIsRejected() throws Exception {
            mockMvc.perform(post("/auth/send-email")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\": \"user@example.com\"}"))
                    .andExpect(status().isBadRequest());

            verifyNoInteractions(userApprovalEmailService);
        }

        @Test
        @DisplayName("a delivery failure is a 502, not a 200 with a false flag")
        void deliveryFailureIsNotReportedAsSuccess() throws Exception {
            willThrow(new EmailProviderException(Reason.PROVIDER_UNAVAILABLE, "gmail-smtp", "smtp down"))
                    .given(userApprovalEmailService).send(any());

            mockMvc.perform(post("/auth/send-email")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"templateType": "denied", "email": "user@example.com", "name": "Alex"}
                                    """))
                    .andExpect(status().isBadGateway())
                    .andExpect(jsonPath("$.deliveryUncertain").value(true));
        }
    }

    // -- token-based decisions -------------------------------------------------------------------

    @Nested
    @DisplayName("GET /auth/approve and /auth/deny")
    class TokenDecisions {

        @Test
        @DisplayName("a valid token approves and notifies the user")
        void approvesWithValidToken() throws Exception {
            given(approvalTokenService.verifyApprovalTokenWithClaims("good-token"))
                    .willReturn(Map.of("email", "user@example.com", "name", "Alex"));
            givenSendSucceeds();

            mockMvc.perform(get("/auth/approve").param("token", "good-token"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("APPROVED"))
                    .andExpect(jsonPath("$.email").value("user@example.com"))
                    .andExpect(jsonPath("$.messageId").value("<id@smtp>"));
        }

        @Test
        @DisplayName("a valid token denies and notifies the user")
        void deniesWithValidToken() throws Exception {
            given(approvalTokenService.verifyApprovalTokenWithClaims("good-token"))
                    .willReturn(Map.of("email", "user@example.com", "name", "Alex"));
            givenSendSucceeds();

            mockMvc.perform(get("/auth/deny").param("token", "good-token"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("DENIED"));
        }

        @Test
        @DisplayName("an invalid token is refused and nothing is sent")
        void refusesInvalidToken() throws Exception {
            given(approvalTokenService.verifyApprovalTokenWithClaims(any())).willReturn(null);

            mockMvc.perform(get("/auth/approve").param("token", "bad-token"))
                    .andExpect(status().isBadRequest());

            verifyNoInteractions(userApprovalEmailService);
        }

        @Test
        @DisplayName("a token without an email claim is refused")
        void refusesTokenWithoutEmail() throws Exception {
            given(approvalTokenService.verifyApprovalTokenWithClaims(any()))
                    .willReturn(new java.util.HashMap<>(Map.of("name", "Alex")));

            mockMvc.perform(get("/auth/approve").param("token", "odd-token"))
                    .andExpect(status().isBadRequest());

            verifyNoInteractions(userApprovalEmailService);
        }

        @Test
        @DisplayName("a missing token parameter is a 400, not a 500")
        void missingTokenParameter() throws Exception {
            mockMvc.perform(get("/auth/approve"))
                    .andExpect(status().isBadRequest());

            verifyNoInteractions(userApprovalEmailService);
        }
    }

    // -- manual decisions --------------------------------------------------------------------------

    @Nested
    @DisplayName("POST /auth/manual-approve and /auth/manual-deny")
    class ManualDecisions {

        @Test
        @DisplayName("records an approval and notifies the user")
        void manualApprove() throws Exception {
            givenSendSucceeds();

            mockMvc.perform(post("/auth/manual-approve")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\": \"user@example.com\", \"name\": \"Alex\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("APPROVED"))
                    .andExpect(jsonPath("$.name").value("Alex"));
        }

        @Test
        @DisplayName("defaults the display name when absent")
        void defaultsMissingName() throws Exception {
            givenSendSucceeds();

            mockMvc.perform(post("/auth/manual-deny")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\": \"user@example.com\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("DENIED"))
                    .andExpect(jsonPath("$.name").value("User"));
        }

        @Test
        @DisplayName("requires a well-formed email")
        void validatesEmail() throws Exception {
            mockMvc.perform(post("/auth/manual-approve")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\": \"nope\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.validationErrors[?(@.field == 'email')]").exists());

            verifyNoInteractions(userApprovalEmailService);
        }
    }
}
