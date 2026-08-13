package com.hoseacodes.emailintegrator.brevo;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.hoseacodes.emailintegrator.email.EmailAddress;
import com.hoseacodes.emailintegrator.email.EmailProviderException;
import com.hoseacodes.emailintegrator.email.EmailProviderException.Reason;
import com.hoseacodes.emailintegrator.email.MessageVariant;
import com.hoseacodes.emailintegrator.email.SendEmailCommand;
import com.hoseacodes.emailintegrator.email.SendEmailResult;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * Integration tests for the Brevo adapter, against a real HTTP server.
 *
 * <p><b>Why WireMock and not a mocked {@code RestClient}.</b> Mocking the HTTP client would only
 * assert that this class calls methods we already know it calls. The behaviour actually worth
 * proving lives below that line: how a 429 is classified, whether a read timeout is
 * distinguished from a refused connection, what happens when the body is truncated. Those need
 * a real socket, real status codes, and real delays. WireMock provides them without ever
 * touching Brevo — which matters, because a test suite that reached the live API would send
 * real mail and would fail whenever the network did.
 *
 * <p>Failure paths get more coverage than the happy path on purpose. The happy path is exercised
 * constantly in manual use; the 429-at-3am path is exercised exactly once, in production, unless
 * it is tested here.
 */
class BrevoEmailProviderTest {

    private static final String SEND_PATH = "/v3/smtp/email";
    private static final String API_KEY = "test-api-key-not-a-real-credential";

    /**
     * One server for the whole class, reset between tests.
     *
     * <p>Started per-test originally, which was intermittently flaky: stopping and restarting on a
     * fresh dynamic port every test churns ports fast enough that a connection can occasionally be
     * refused before the new server is accepting, producing a failure with an empty request
     * journal. It surfaced once in a run that changed only documentation — which is exactly how a
     * flaky test erodes trust in a suite, because the first instinct is to blame the change.
     *
     * <p>{@code resetAll()} clears both stubs and the request journal, so tests stay isolated
     * without paying for a server lifecycle each time.
     */
    private static WireMockServer wireMock;

    private BrevoEmailProvider provider;

    @BeforeAll
    static void startServer() {
        wireMock = new WireMockServer(options().dynamicPort());
        wireMock.start();
    }

    @AfterAll
    static void stopServer() {
        wireMock.stop();
    }

    @BeforeEach
    void resetServer() {
        wireMock.resetAll();
        provider = providerWith(wireMock.baseUrl(), Duration.ofSeconds(2), Duration.ofSeconds(2));
    }

    /** Builds the provider exactly as Spring does, so the real timeout configuration is covered too. */
    private static BrevoEmailProvider providerWith(String baseUrl, Duration connect, Duration read) {
        BrevoProperties properties = new BrevoProperties(API_KEY, baseUrl, connect, read);
        RestClient restClient = new BrevoClientConfig().brevoRestClient(properties);
        return new BrevoEmailProvider(restClient, properties);
    }

    private static SendEmailCommand simpleCommand() {
        return new SendEmailCommand(
                new EmailAddress("sender@example.com", "Example Sender"),
                List.of(new EmailAddress("recipient@example.com", "Recipient")),
                List.of(), List.of(), null,
                "Subject line", "<p>Hello</p>", "Hello",
                List.of());
    }

    // -- success -------------------------------------------------------------------------------

    @Nested
    @DisplayName("successful sends")
    class Success {

        @Test
        @DisplayName("returns the provider message id and reports the provider name")
        void returnsMessageId() {
            wireMock.stubFor(post(urlEqualTo(SEND_PATH)).willReturn(aResponse()
                    .withStatus(201)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"messageId\":\"<202608.7@smtp-relay.brevo.com>\"}")));

            SendEmailResult result = provider.send(simpleCommand());

            assertThat(result.messageIds()).containsExactly("<202608.7@smtp-relay.brevo.com>");
            assertThat(result.provider()).isEqualTo("brevo");
        }

        @Test
        @DisplayName("sends the API key as a header, never in the body or query string")
        void sendsApiKeyHeader() {
            stubCreated();

            provider.send(simpleCommand());

            wireMock.verify(postRequestedFor(urlEqualTo(SEND_PATH))
                    .withHeader("api-key", equalTo(API_KEY)));
        }

        @Test
        @DisplayName("maps the command onto Brevo's wire format")
        void mapsCommandToWireFormat() {
            stubCreated();

            provider.send(new SendEmailCommand(
                    new EmailAddress("sender@example.com", "Example Sender"),
                    List.of(new EmailAddress("to@example.com", "To Person")),
                    List.of(new EmailAddress("cc@example.com", null)),
                    List.of(new EmailAddress("bcc@example.com", null)),
                    new EmailAddress("reply@example.com", "Reply Desk"),
                    "Quarterly update", "<p>Body</p>", "Body",
                    List.of()));

            wireMock.verify(postRequestedFor(urlEqualTo(SEND_PATH))
                    .withRequestBody(matchingJsonPath("$.sender.email", equalTo("sender@example.com")))
                    .withRequestBody(matchingJsonPath("$.sender.name", equalTo("Example Sender")))
                    .withRequestBody(matchingJsonPath("$.to[0].email", equalTo("to@example.com")))
                    .withRequestBody(matchingJsonPath("$.cc[0].email", equalTo("cc@example.com")))
                    .withRequestBody(matchingJsonPath("$.bcc[0].email", equalTo("bcc@example.com")))
                    .withRequestBody(matchingJsonPath("$.replyTo.email", equalTo("reply@example.com")))
                    .withRequestBody(matchingJsonPath("$.subject", equalTo("Quarterly update")))
                    .withRequestBody(matchingJsonPath("$.htmlContent", equalTo("<p>Body</p>")))
                    .withRequestBody(matchingJsonPath("$.textContent", equalTo("Body"))));
        }

        @Test
        @DisplayName("omits absent optional fields rather than sending explicit nulls")
        void omitsNullFields() {
            stubCreated();

            provider.send(simpleCommand()); // no cc, bcc, or replyTo

            wireMock.verify(postRequestedFor(urlEqualTo(SEND_PATH))
                    .withRequestBody(matchingJsonPath("$[?(!@.cc)]"))
                    .withRequestBody(matchingJsonPath("$[?(!@.bcc)]"))
                    .withRequestBody(matchingJsonPath("$[?(!@.replyTo)]"))
                    .withRequestBody(matchingJsonPath("$[?(!@.messageVersions)]")));
        }

        @Test
        @DisplayName("multi-variant send maps to messageVersions and returns every message id")
        void multiVariantSend() {
            wireMock.stubFor(post(urlEqualTo(SEND_PATH)).willReturn(aResponse()
                    .withStatus(201)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"messageIds\":[\"<id-1@brevo>\",\"<id-2@brevo>\"]}")));

            SendEmailResult result = provider.send(new SendEmailCommand(
                    new EmailAddress("sender@example.com", null),
                    List.of(), List.of(), List.of(), null,
                    "Default subject", "<p>Default</p>", null,
                    List.of(
                            new MessageVariant(List.of(new EmailAddress("a@example.com", null)), "For A", "<p>A</p>"),
                            new MessageVariant(List.of(new EmailAddress("b@example.com", null)), null, null))));

            assertThat(result.messageIds()).containsExactly("<id-1@brevo>", "<id-2@brevo>");

            wireMock.verify(postRequestedFor(urlEqualTo(SEND_PATH))
                    .withRequestBody(matchingJsonPath("$.messageVersions[0].to[0].email", equalTo("a@example.com")))
                    .withRequestBody(matchingJsonPath("$.messageVersions[0].subject", equalTo("For A")))
                    .withRequestBody(matchingJsonPath("$.messageVersions[1].to[0].email", equalTo("b@example.com")))
                    // Brevo requires a top-level `to` even when messageVersions is present; when the
                    // caller supplied recipients only inside variants, the first variant stands in.
                    .withRequestBody(matchingJsonPath("$.to[0].email", equalTo("a@example.com"))));
        }
    }

    // -- provider rejects ----------------------------------------------------------------------

    @Nested
    @DisplayName("provider rejections")
    class Rejections {

        @Test
        @DisplayName("400 is a rejected request that must not be retried")
        void badRequest() {
            wireMock.stubFor(post(urlEqualTo(SEND_PATH)).willReturn(aResponse()
                    .withStatus(400)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"code\":\"invalid_parameter\",\"message\":\"to is invalid\"}")));

            EmailProviderException e = catchThrowableOfType(
                    () -> provider.send(simpleCommand()), EmailProviderException.class);

            assertThat(e.getReason()).isEqualTo(Reason.REQUEST_REJECTED);
            assertThat(e.isSideEffectPossible()).isFalse();
            assertThat(e.getProvider()).isEqualTo("brevo");
        }

        @ParameterizedTest(name = "HTTP {0} is a provider authentication failure")
        @ValueSource(ints = {401, 403})
        @DisplayName("401 and 403 mean OUR credentials are wrong, not the caller's")
        void authFailures(int status) {
            wireMock.stubFor(post(urlEqualTo(SEND_PATH)).willReturn(aResponse()
                    .withStatus(status)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"code\":\"unauthorized\",\"message\":\"Key not found\"}")));

            EmailProviderException e = catchThrowableOfType(
                    () -> provider.send(simpleCommand()), EmailProviderException.class);

            assertThat(e.getReason()).isEqualTo(Reason.PROVIDER_AUTH_FAILED);
            assertThat(e.isSideEffectPossible()).isFalse();
        }

        @Test
        @DisplayName("the provider's error message is not copied into the exception")
        void doesNotLeakProviderErrorMessage() {
            wireMock.stubFor(post(urlEqualTo(SEND_PATH)).willReturn(aResponse()
                    .withStatus(400)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"code\":\"invalid_parameter\","
                            + "\"message\":\"account 12345 sender victim@private.example is unverified\"}")));

            EmailProviderException e = catchThrowableOfType(
                    () -> provider.send(simpleCommand()), EmailProviderException.class);

            // The machine-readable code is useful and safe; the prose can name accounts and
            // addresses, so it stays in the debug log and out of anything caller-facing.
            assertThat(e.getMessage()).contains("invalid_parameter");
            assertThat(e.getMessage()).doesNotContain("victim@private.example");
            assertThat(e.getMessage()).doesNotContain("12345");
        }
    }

    // -- rate limiting -------------------------------------------------------------------------

    @Test
    @DisplayName("429 is rate limiting and carries the provider's Retry-After")
    void rateLimited() {
        wireMock.stubFor(post(urlEqualTo(SEND_PATH)).willReturn(aResponse()
                .withStatus(429)
                .withHeader("Content-Type", "application/json")
                .withHeader("Retry-After", "42")
                .withBody("{\"code\":\"too_many_requests\",\"message\":\"slow down\"}")));

        EmailProviderException e = catchThrowableOfType(
                () -> provider.send(simpleCommand()), EmailProviderException.class);

        assertThat(e.getReason()).isEqualTo(Reason.RATE_LIMITED);
        assertThat(e.getRetryAfter()).isEqualTo(Duration.ofSeconds(42));
        // Rate limiting happens before the message is accepted, so nothing was sent.
        assertThat(e.isSideEffectPossible()).isFalse();
    }

    @Test
    @DisplayName("429 without a usable Retry-After still classifies correctly")
    void rateLimitedWithoutRetryAfter() {
        wireMock.stubFor(post(urlEqualTo(SEND_PATH)).willReturn(aResponse()
                .withStatus(429)
                .withHeader("Retry-After", "Wed, 21 Oct 2026 07:28:00 GMT")
                .withBody("{}")));

        EmailProviderException e = catchThrowableOfType(
                () -> provider.send(simpleCommand()), EmailProviderException.class);

        assertThat(e.getReason()).isEqualTo(Reason.RATE_LIMITED);
        assertThat(e.getRetryAfter()).isNull(); // HTTP-date form is not parsed; absence is honest
    }

    // -- provider failures ---------------------------------------------------------------------

    @ParameterizedTest(name = "HTTP {0} marks the provider unavailable")
    @ValueSource(ints = {500, 502, 503})
    @DisplayName("5xx is side-effect-possible: the message may already have been queued")
    void serverErrors(int status) {
        wireMock.stubFor(post(urlEqualTo(SEND_PATH)).willReturn(aResponse()
                .withStatus(status).withBody("{}")));

        EmailProviderException e = catchThrowableOfType(
                () -> provider.send(simpleCommand()), EmailProviderException.class);

        assertThat(e.getReason()).isEqualTo(Reason.PROVIDER_UNAVAILABLE);
        // This is the assertion that stops someone bolting on a naive retry later.
        assertThat(e.isSideEffectPossible()).isTrue();
    }

    // -- malformed responses -------------------------------------------------------------------

    @Test
    @DisplayName("a 2xx with an unparseable body is treated as possibly-sent, not as success")
    void malformedSuccessBody() {
        wireMock.stubFor(post(urlEqualTo(SEND_PATH)).willReturn(aResponse()
                .withStatus(201)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"messageId\": \"truncated")));

        EmailProviderException e = catchThrowableOfType(
                () -> provider.send(simpleCommand()), EmailProviderException.class);

        assertThat(e.getReason()).isEqualTo(Reason.PROVIDER_UNAVAILABLE);
        assertThat(e.isSideEffectPossible()).isTrue();
    }

    @Test
    @DisplayName("a 2xx with no message id is a failure, not a silent success")
    void successBodyWithoutMessageId() {
        wireMock.stubFor(post(urlEqualTo(SEND_PATH)).willReturn(aResponse()
                .withStatus(201)
                .withHeader("Content-Type", "application/json")
                .withBody("{}")));

        assertThatThrownBy(() -> provider.send(simpleCommand()))
                .isInstanceOf(EmailProviderException.class)
                .hasMessageContaining("without a message id");
    }

    // -- transport failures --------------------------------------------------------------------

    @Test
    @DisplayName("a read timeout is side-effect-possible — the request was sent, the outcome is unknown")
    void readTimeout() {
        // The provider takes longer than our read timeout allows.
        wireMock.stubFor(post(urlEqualTo(SEND_PATH)).willReturn(aResponse()
                .withStatus(201)
                .withFixedDelay(1500)
                .withBody("{\"messageId\":\"<sent-anyway@brevo>\"}")));

        BrevoEmailProvider impatient =
                providerWith(wireMock.baseUrl(), Duration.ofSeconds(2), Duration.ofMillis(250));

        EmailProviderException e = catchThrowableOfType(
                () -> impatient.send(simpleCommand()), EmailProviderException.class);

        assertThat(e.getReason()).isEqualTo(Reason.TIMEOUT);
        // The stub proves the point: the send DID happen server-side. Retrying would duplicate it.
        assertThat(e.isSideEffectPossible()).isTrue();
    }

    @Test
    @DisplayName("a refused connection is NOT side-effect-possible — the request never left")
    void connectionRefused() throws IOException {
        BrevoEmailProvider unreachable =
                providerWith("http://localhost:" + unusedPort(), Duration.ofMillis(500), Duration.ofSeconds(2));

        EmailProviderException e = catchThrowableOfType(
                () -> unreachable.send(simpleCommand()), EmailProviderException.class);

        assertThat(e.getReason()).isEqualTo(Reason.CONNECT_FAILED);
        // The distinction that makes a retry policy safe rather than reckless.
        assertThat(e.isSideEffectPossible()).isFalse();
    }

    // Note: there is deliberately no DNS-failure test here. Resolver behaviour for a bogus
    // hostname varies by machine and network — some resolvers hang, some wildcard-resolve — so
    // such a test would be slow and flaky rather than informative. UnknownHostException is
    // handled in isConnectFailure() and covered by ConnectFailureClassificationTest instead.

    // -- no automatic retries ------------------------------------------------------------------

    /**
     * Regression tests for a real defect found by these tests.
     *
     * <p>Apache HttpClient 5 enables automatic retries by default: it re-sends on transient I/O
     * failures, and on 429 and 503 it waits out the provider's {@code Retry-After} before trying
     * again. Because Spring selects the HTTP client by classpath scan, that behaviour arrived
     * through a transitive dependency and applied itself to a non-idempotent email send — every
     * throttled or overloaded response would have silently delivered a duplicate message.
     *
     * <p>It surfaced as a hung test suite: the 429 stub advertised {@code Retry-After: 42} and
     * the client dutifully slept for 42 seconds. {@code BrevoClientConfig} now disables retries
     * explicitly; these assertions keep it that way.
     */
    @Nested
    @DisplayName("no automatic retries")
    class NoRetries {

        @ParameterizedTest(name = "HTTP {0} is attempted exactly once")
        @ValueSource(ints = {429, 500, 503})
        @DisplayName("failures the HTTP client would retry by default are attempted once")
        void doesNotRetryRetryableStatuses(int status) {
            wireMock.stubFor(post(urlEqualTo(SEND_PATH)).willReturn(aResponse()
                    .withStatus(status)
                    // The header that previously caused the client to sleep and re-send.
                    .withHeader("Retry-After", "42")
                    .withBody("{}")));

            catchThrowableOfType(() -> provider.send(simpleCommand()), EmailProviderException.class);

            wireMock.verify(1, postRequestedFor(urlEqualTo(SEND_PATH)));
        }

        @Test
        @DisplayName("a 429 fails fast instead of waiting out Retry-After inside the request thread")
        void doesNotSleepOnRetryAfter() {
            wireMock.stubFor(post(urlEqualTo(SEND_PATH)).willReturn(aResponse()
                    .withStatus(429)
                    .withHeader("Retry-After", "42")
                    .withBody("{}")));

            long startMillis = System.currentTimeMillis();
            catchThrowableOfType(() -> provider.send(simpleCommand()), EmailProviderException.class);
            long elapsedMillis = System.currentTimeMillis() - startMillis;

            // Blocking a Tomcat worker for 42 seconds because a provider said "slow down" is how
            // one throttled dependency becomes a service-wide outage. Fail fast; let the caller
            // decide when to come back.
            assertThat(elapsedMillis).isLessThan(5_000);
        }
    }

    // -- helpers -------------------------------------------------------------------------------

    private void stubCreated() {
        wireMock.stubFor(post(urlEqualTo(SEND_PATH)).willReturn(aResponse()
                .withStatus(201)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"messageId\":\"<generated@brevo>\"}")));
    }

    /** Binds and immediately releases a port, so connecting to it is refused rather than hanging. */
    private static int unusedPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
