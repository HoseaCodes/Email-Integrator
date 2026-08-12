package com.hoseacodes.emailintegrator.security;

import com.hoseacodes.emailintegrator.email.SendEmailResult;
import com.hoseacodes.emailintegrator.service.EmailDeliveryService;
import com.hoseacodes.emailintegrator.service.UserApprovalEmailService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tests the security boundary with the real filter chain in place.
 *
 * <p>This test exists because {@code EmailControllerTest} runs with filters disabled in order to
 * test controller behaviour in isolation. That is only legitimate if the security rules are
 * genuinely asserted somewhere — otherwise "disable security to make the tests pass" is exactly
 * the anti-pattern it sounds like. This is that somewhere, and it uses the full application
 * context so the rules under test are the ones that ship.
 *
 * <p>The endpoints below were all anonymously reachable before this work, on a live public
 * host. {@code /auth/send-email} in particular would send HTML mail, from a real domain, to any
 * recipient a stranger named. These assertions are the regression tests for that.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ApiKeySecurityTest {

    private static final String HEADER = "X-API-Key";
    private static final String VALID_KEY = "test-client-key-0123456789abcdef0123456789abcdef";

    @Autowired
    private MockMvc mockMvc;

    /** Mocked so that no test in this class can send real mail, whatever the outcome. */
    @MockBean
    private EmailDeliveryService emailDeliveryService;

    @MockBean
    private UserApprovalEmailService userApprovalEmailService;

    private static final String SEND_BODY = """
            {
              "to": [{"email": "recipient@example.com"}],
              "subject": "Test",
              "textContent": "hello"
            }
            """;

    // -- endpoints that must require credentials ----------------------------------------------

    @Nested
    @DisplayName("protected endpoints")
    class Protected {

        @Test
        @DisplayName("POST /email is rejected without a key")
        void sendEmailRequiresKey() throws Exception {
            mockMvc.perform(post("/email")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(SEND_BODY))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));

            verifyNoInteractions(emailDeliveryService);
        }

        @Test
        @DisplayName("POST /auth/send-email is rejected without a key — this was the open relay")
        void sendTemplatedEmailRequiresKey() throws Exception {
            mockMvc.perform(post("/auth/send-email")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "templateType": "password-reset",
                                      "email": "victim@example.com",
                                      "resetUrl": "https://attacker.example/harvest"
                                    }
                                    """))
                    .andExpect(status().isUnauthorized());

            // The whole point: no mail leaves the building for an anonymous caller.
            verifyNoInteractions(userApprovalEmailService);
        }

        @Test
        @DisplayName("POST /auth/manual-approve is rejected without a key")
        void manualApproveRequiresKey() throws Exception {
            mockMvc.perform(post("/auth/manual-approve")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"someone@example.com\"}"))
                    .andExpect(status().isUnauthorized());

            verifyNoInteractions(userApprovalEmailService);
        }

        @Test
        @DisplayName("POST /auth/manual-deny is rejected without a key")
        void manualDenyRequiresKey() throws Exception {
            mockMvc.perform(post("/auth/manual-deny")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"someone@example.com\"}"))
                    .andExpect(status().isUnauthorized());

            verifyNoInteractions(userApprovalEmailService);
        }

        @Test
        @DisplayName("POST /api/spring-mail/send is rejected without a key")
        void springMailSendRequiresKey() throws Exception {
            mockMvc.perform(post("/api/spring-mail/send")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"to\":[\"someone@example.com\"],\"subject\":\"x\",\"textContent\":\"y\"}"))
                    .andExpect(status().isUnauthorized());
        }
    }

    // -- credential handling -------------------------------------------------------------------

    @Nested
    @DisplayName("credential checks")
    class Credentials {

        @Test
        @DisplayName("a wrong key is rejected")
        void wrongKeyRejected() throws Exception {
            mockMvc.perform(post("/email")
                            .header(HEADER, "wrong-key-that-is-long-enough-to-look-plausible-xx")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(SEND_BODY))
                    .andExpect(status().isUnauthorized());

            verifyNoInteractions(emailDeliveryService);
        }

        @Test
        @DisplayName("a key that is a prefix of a valid key is rejected")
        void prefixOfValidKeyRejected() throws Exception {
            mockMvc.perform(post("/email")
                            .header(HEADER, VALID_KEY.substring(0, VALID_KEY.length() - 1))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(SEND_BODY))
                    .andExpect(status().isUnauthorized());

            verifyNoInteractions(emailDeliveryService);
        }

        @Test
        @DisplayName("an empty key header is rejected")
        void emptyKeyRejected() throws Exception {
            mockMvc.perform(post("/email")
                            .header(HEADER, "")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(SEND_BODY))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("a valid key is accepted and the request reaches the application")
        void validKeyAccepted() throws Exception {
            given(emailDeliveryService.send(any()))
                    .willReturn(SendEmailResult.single("<id@brevo>", "brevo"));

            mockMvc.perform(post("/email")
                            .header(HEADER, VALID_KEY)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(SEND_BODY))
                    .andExpect(status().isAccepted())
                    .andExpect(jsonPath("$.messageIds[0]").value("<id@brevo>"));
        }

        @Test
        @DisplayName("a second configured client's key also works")
        void secondClientKeyAccepted() throws Exception {
            given(emailDeliveryService.send(any()))
                    .willReturn(SendEmailResult.single("<id@brevo>", "brevo"));

            mockMvc.perform(post("/email")
                            .header(HEADER, "other-client-key-fedcba9876543210fedcba98765432")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(SEND_BODY))
                    .andExpect(status().isAccepted());
        }

        @Test
        @DisplayName("the 401 body does not reveal whether the key was missing or merely wrong")
        void unauthenticatedResponseIsUninformative() throws Exception {
            String noKey = mockMvc.perform(post("/email")
                            .contentType(MediaType.APPLICATION_JSON).content(SEND_BODY))
                    .andReturn().getResponse().getContentAsString();

            String badKey = mockMvc.perform(post("/email")
                            .header(HEADER, "definitely-not-a-valid-key-but-long-enough-here")
                            .contentType(MediaType.APPLICATION_JSON).content(SEND_BODY))
                    .andReturn().getResponse().getContentAsString();

            // Bodies differ only by correlation id and timestamp. Telling an attacker "that key
            // exists but is wrong" versus "no key supplied" is free reconnaissance.
            org.assertj.core.api.Assertions.assertThat(stripVolatile(noKey))
                    .isEqualTo(stripVolatile(badKey));
        }

        private static String stripVolatile(String json) {
            return json.replaceAll("\"timestamp\":\"[^\"]+\"", "")
                    .replaceAll("\"errorId\":\"[^\"]+\"", "");
        }
    }

    // -- deliberately public endpoints ---------------------------------------------------------

    @Nested
    @DisplayName("public endpoints")
    class Public {

        @Test
        @DisplayName("the health check is reachable without credentials, for platform probes")
        void healthIsPublic() throws Exception {
            mockMvc.perform(get("/actuator/health"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("UP"));
        }

        @Test
        @DisplayName("health details stay hidden from unauthenticated callers")
        void healthDetailsNotExposed() throws Exception {
            mockMvc.perform(get("/actuator/health"))
                    .andExpect(status().isOk())
                    // Component detail can name hosts and dependency state; the probe only
                    // needs the aggregate status.
                    .andExpect(jsonPath("$.components").doesNotExist());
        }

        @Test
        @DisplayName("approval links are reachable without an API key — the JWT is the credential")
        void approvalLinkIsReachable() throws Exception {
            // A human clicks these from an email client, which cannot attach a header. The
            // signed token in the query string is what authorises the action, and the
            // controller rejects this obviously invalid one.
            mockMvc.perform(get("/auth/approve").param("token", "not-a-valid-jwt"))
                    .andExpect(status().isBadRequest());

            verifyNoInteractions(userApprovalEmailService);
        }

        @Test
        @DisplayName("a tampered approval token is refused rather than trusted")
        void tamperedTokenRefused() throws Exception {
            mockMvc.perform(get("/auth/deny")
                            .param("token", "eyJhbGciOiJIUzI1NiJ9.eyJlbWFpbCI6ImF0dGFja2VyQGV4YW1wbGUuY29tIn0.forged"))
                    .andExpect(status().isBadRequest());

            verifyNoInteractions(userApprovalEmailService);
        }

        @Test
        @DisplayName("the OpenAPI schema is readable without credentials")
        void openApiIsPublic() throws Exception {
            mockMvc.perform(get("/v3/api-docs"))
                    .andExpect(status().isOk());
        }
    }

    // -- default posture -----------------------------------------------------------------------

    @Test
    @DisplayName("an unmapped path is denied rather than allowed")
    void unknownPathIsDeniedByDefault() throws Exception {
        // anyRequest().authenticated() is the last rule, so anything not explicitly permitted —
        // including endpoints nobody has written yet — fails closed.
        mockMvc.perform(get("/some/endpoint/added/later"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("actuator endpoints other than health are not anonymously reachable")
    void otherActuatorEndpointsAreProtected() throws Exception {
        mockMvc.perform(get("/actuator/env"))
                .andExpect(status().is(org.hamcrest.Matchers.not(200)));
    }
}
