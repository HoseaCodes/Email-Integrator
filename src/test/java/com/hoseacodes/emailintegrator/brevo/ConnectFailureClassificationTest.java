package com.hoseacodes.emailintegrator.brevo;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the one piece of logic that decides whether a retry could duplicate an email.
 *
 * <p>{@code isConnectFailure} answers: did the request ever reach the provider? If the
 * connection was never established, the answer is definitively no, and a retry is safe. If the
 * request was written and the response timed out, the answer is unknown, and a retry may send a
 * second copy to a real person.
 *
 * <p>Tested directly rather than through a socket because the inputs that matter —
 * {@link UnknownHostException}, and Apache HttpClient's connect-timeout subclass — cannot be
 * provoked reliably without depending on the machine's DNS resolver or on library internals.
 *
 * <p>The default case is the important one: anything unrecognised is treated as
 * <em>not</em> a connect failure, so an unfamiliar exception degrades toward "assume it might
 * have sent" rather than toward a duplicate message.
 */
class ConnectFailureClassificationTest {

    @Test
    @DisplayName("connection refused means the request never left")
    void connectException() {
        assertThat(BrevoEmailProvider.isConnectFailure(
                new ResourceAccessException("I/O error", new ConnectException("Connection refused"))))
                .isTrue();
    }

    @Test
    @DisplayName("DNS failure means the request never left")
    void unknownHost() {
        assertThat(BrevoEmailProvider.isConnectFailure(
                new ResourceAccessException("I/O error", new UnknownHostException("api.brevo.com"))))
                .isTrue();
    }

    @Test
    @DisplayName("a connect timeout means the request never left")
    void connectTimeoutByMessage() {
        assertThat(BrevoEmailProvider.isConnectFailure(
                new ResourceAccessException("I/O error", new SocketTimeoutException("connect timed out"))))
                .isTrue();
    }

    @Test
    @DisplayName("Apache HttpClient's ConnectTimeoutException is recognised by type name")
    void apacheConnectTimeoutSubclass() {
        // Mirrors org.apache.hc.client5.http.ConnectTimeoutException, which extends
        // SocketTimeoutException. Matched by simple name so this class need not depend on
        // whichever HTTP client the request factory happens to select.
        class ConnectTimeoutException extends SocketTimeoutException {
            ConnectTimeoutException() {
                super("connect timed out after 3000 ms");
            }
        }
        assertThat(BrevoEmailProvider.isConnectFailure(
                new ResourceAccessException("I/O error", new ConnectTimeoutException())))
                .isTrue();
    }

    @Test
    @DisplayName("a READ timeout is not a connect failure — the request may have been delivered")
    void readTimeout() {
        assertThat(BrevoEmailProvider.isConnectFailure(
                new ResourceAccessException("I/O error", new SocketTimeoutException("Read timed out"))))
                .isFalse();
    }

    @Test
    @DisplayName("an unrecognised I/O error is treated as possibly-delivered, the safe default")
    void unrecognisedFailureIsNotAConnectFailure() {
        assertThat(BrevoEmailProvider.isConnectFailure(
                new ResourceAccessException("I/O error", new IOException("broken pipe"))))
                .isFalse();
    }

    @Test
    @DisplayName("classification walks the whole cause chain, not just the top exception")
    void inspectsNestedCauses() {
        Throwable nested = new ResourceAccessException("I/O error",
                new IOException("wrapper", new ConnectException("Connection refused")));

        assertThat(BrevoEmailProvider.isConnectFailure(nested)).isTrue();
    }

    @Test
    @DisplayName("a self-referencing cause chain terminates instead of looping forever")
    void selfReferencingCauseDoesNotLoop() {
        // Defensive: some libraries produce exceptions whose getCause() returns themselves.
        // Without the guard in isConnectFailure this would spin the request thread indefinitely.
        IOException selfReferencing = new IOException("looping") {
            @Override
            public synchronized Throwable getCause() {
                return this;
            }
        };

        assertThat(BrevoEmailProvider.isConnectFailure(selfReferencing)).isFalse();
    }
}
