package com.hoseacodes.emailintegrator.brevo;

import com.hoseacodes.emailintegrator.brevo.wire.BrevoWire;
import com.hoseacodes.emailintegrator.email.EmailAddress;
import com.hoseacodes.emailintegrator.email.EmailProvider;
import com.hoseacodes.emailintegrator.email.EmailProviderException;
import com.hoseacodes.emailintegrator.email.EmailProviderException.Reason;
import com.hoseacodes.emailintegrator.email.MessageVariant;
import com.hoseacodes.emailintegrator.email.SendEmailCommand;
import com.hoseacodes.emailintegrator.email.SendEmailResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Sends email through Brevo's transactional API.
 *
 * <p>This class owns every Brevo-specific concern — wire format, the {@code api-key} header,
 * and the meaning of each status code — and exposes none of them. Callers see only
 * {@link SendEmailResult} or {@link EmailProviderException}.
 *
 * <h2>Why the vendor SDK was removed</h2>
 * The previous implementation used {@code sib-api-v3-sdk}, which authenticated through a
 * process-global static ({@code Configuration.getDefaultApiClient()}), offered no timeout
 * configuration, and pulled Maven 2.0.6 build tooling onto the runtime classpath. Brevo's
 * transactional send is a single POST, so it is issued directly. See
 * {@code docs/adr/0001-brevo-http-client-over-vendor-sdk.md}.
 *
 * <h2>Retries</h2>
 * This class does not retry. Sending email is not idempotent, and several failure modes leave
 * it genuinely unknown whether the message went out. {@link Reason#isSideEffectPossible()}
 * records that per failure so a future retry or idempotency layer can decide safely rather
 * than guessing. See {@code docs/RELIABILITY.md}.
 */
@Component
public class BrevoEmailProvider implements EmailProvider {

    static final String PROVIDER_NAME = "brevo";
    private static final String SEND_PATH = "/v3/smtp/email";

    private static final Logger log = LoggerFactory.getLogger(BrevoEmailProvider.class);

    private final RestClient restClient;
    private final BrevoProperties properties;

    BrevoEmailProvider(RestClient brevoRestClient, BrevoProperties properties) {
        this.restClient = brevoRestClient;
        this.properties = properties;
    }

    @Override
    public String name() {
        return PROVIDER_NAME;
    }

    @Override
    public SendEmailResult send(SendEmailCommand command) {
        BrevoWire.SendRequest request = toWireRequest(command);
        long startNanos = System.nanoTime();

        try {
            SendEmailResult result = execute(request);
            log.info("Brevo send succeeded: recipients={} variants={} messageIds={} durationMs={}",
                    command.totalRecipientCount(), command.variants().size(),
                    result.messageIds().size(), elapsedMillis(startNanos));
            return result;

        } catch (EmailProviderException e) {
            // Log the classification and timing; never the payload, the recipients, or the key.
            log.warn("Brevo send failed: reason={} sideEffectPossible={} durationMs={} detail={}",
                    e.getReason(), e.isSideEffectPossible(), elapsedMillis(startNanos), e.getMessage());
            throw e;

        } catch (ResourceAccessException e) {
            // Transport-level failure: the request factory could not complete the exchange.
            throw transportFailure(e, startNanos);
        }
    }

    private SendEmailResult execute(BrevoWire.SendRequest request) {
        return restClient.post()
                .uri(SEND_PATH)
                .header("api-key", properties.apiKey())
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                // exchange() rather than retrieve(): it hands us the raw status and body so every
                // status code is mapped explicitly here, instead of relying on RestClient's default
                // "throw on 4xx/5xx" behaviour and unpacking a generic exception afterwards.
                .exchange((req, response) -> handleResponse(response));
    }

    private SendEmailResult handleResponse(RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse response)
            throws IOException {

        HttpStatusCode status = response.getStatusCode();

        if (status.is2xxSuccessful()) {
            BrevoWire.SendResponse body;
            try {
                body = response.bodyTo(BrevoWire.SendResponse.class);
            } catch (RestClientException e) {
                // Brevo accepted the request but we cannot parse the reply. The mail was most
                // likely sent, so this is side-effect-possible and must not be blind-retried.
                throw new EmailProviderException(Reason.PROVIDER_UNAVAILABLE, PROVIDER_NAME,
                        "Brevo returned " + status.value() + " with an unreadable body", e);
            }

            List<String> messageIds = body == null ? List.of() : body.allMessageIds();
            if (messageIds.isEmpty()) {
                throw new EmailProviderException(Reason.PROVIDER_UNAVAILABLE, PROVIDER_NAME,
                        "Brevo returned " + status.value() + " without a message id");
            }
            return new SendEmailResult(messageIds, PROVIDER_NAME);
        }

        throw mapErrorStatus(status, response);
    }

    /**
     * Translates a non-2xx Brevo response into an application-level failure.
     *
     * <p>The distinction that matters most: a Brevo <em>authentication</em> failure is our
     * configuration problem, not the API caller's. It must not surface to the caller as 401 —
     * they supplied valid credentials to us. It becomes a 502 upstream.
     */
    private EmailProviderException mapErrorStatus(
            HttpStatusCode status,
            RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse response) {

        String providerCode = readErrorCode(response);
        int code = status.value();

        if (code == 429) {
            Duration retryAfter = parseRetryAfter(response.getHeaders().getFirst("Retry-After"));
            return new EmailProviderException(Reason.RATE_LIMITED, PROVIDER_NAME,
                    "Brevo rate limit exceeded" + suffix(providerCode), retryAfter, null);
        }
        if (code == 401 || code == 403) {
            return new EmailProviderException(Reason.PROVIDER_AUTH_FAILED, PROVIDER_NAME,
                    "Brevo rejected our API credentials (HTTP " + code + ")" + suffix(providerCode));
        }
        if (status.is5xxServerError()) {
            return new EmailProviderException(Reason.PROVIDER_UNAVAILABLE, PROVIDER_NAME,
                    "Brevo returned HTTP " + code + suffix(providerCode));
        }
        // Every remaining 4xx means Brevo considered our request malformed.
        return new EmailProviderException(Reason.REQUEST_REJECTED, PROVIDER_NAME,
                "Brevo rejected the request (HTTP " + code + ")" + suffix(providerCode));
    }

    /**
     * Reads Brevo's machine-readable error {@code code} only.
     *
     * <p>The human-readable {@code message} can echo recipient addresses and account detail, so
     * it is logged at DEBUG and never attached to an exception that may reach an API response.
     */
    private String readErrorCode(RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse response) {
        try {
            BrevoWire.ErrorResponse error = response.bodyTo(BrevoWire.ErrorResponse.class);
            if (error == null) {
                return null;
            }
            if (log.isDebugEnabled()) {
                log.debug("Brevo error body: code={} message={}", error.code(), error.message());
            }
            return error.code();
        } catch (RestClientException e) {
            log.debug("Brevo error body was not parseable JSON", e);
            return null;
        }
    }

    /**
     * Classifies a transport failure by whether the request could have reached Brevo.
     *
     * <p>A refused connection or DNS failure means Brevo never saw the request — safe to retry.
     * A read timeout means the request was written and the outcome is unknown — not safe.
     * Distinguishing them is what makes a future retry policy sound rather than reckless.
     */
    private EmailProviderException transportFailure(ResourceAccessException e, long startNanos) {
        boolean neverConnected = isConnectFailure(e);
        Reason reason = neverConnected ? Reason.CONNECT_FAILED : Reason.TIMEOUT;
        String detail = neverConnected
                ? "could not connect to Brevo"
                : "no response from Brevo within the read timeout (" + properties.readTimeout() + ")";

        log.warn("Brevo send failed: reason={} sideEffectPossible={} durationMs={} detail={}",
                reason, reason.isSideEffectPossible(), elapsedMillis(startNanos), detail);

        return new EmailProviderException(reason, PROVIDER_NAME, detail, e);
    }

    /**
     * Walks the cause chain looking for evidence the TCP connection was never established.
     *
     * <p>Connect and read timeouts can both surface as {@link SocketTimeoutException}, so where
     * the exception type alone is ambiguous the message is inspected. Anything unrecognised is
     * treated as a read timeout — the conservative choice, because it marks the send as
     * possibly-delivered and therefore not safe to retry automatically.
     */
    // Package-private rather than private so the classification can be unit-tested directly.
    // Some inputs it must handle — UnknownHostException in particular — cannot be provoked
    // reliably through a real socket without depending on the machine's DNS resolver.
    static boolean isConnectFailure(Throwable throwable) {
        for (Throwable t = throwable; t != null; t = t.getCause()) {
            if (t instanceof ConnectException || t instanceof UnknownHostException) {
                return true;
            }
            // Apache HttpClient signals a connect timeout with its own subclass of
            // SocketTimeoutException; matched by name to avoid depending on that library here.
            String type = t.getClass().getSimpleName();
            if ("ConnectTimeoutException".equals(type) || "HttpHostConnectException".equals(type)) {
                return true;
            }
            if (t instanceof SocketTimeoutException && t.getMessage() != null
                    && t.getMessage().toLowerCase().contains("connect")) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }

    /** Parses the seconds form of {@code Retry-After}; the HTTP-date form is ignored. */
    private static Duration parseRetryAfter(String headerValue) {
        if (headerValue == null || headerValue.isBlank()) {
            return null;
        }
        try {
            long seconds = Long.parseLong(headerValue.trim());
            return seconds >= 0 ? Duration.ofSeconds(seconds) : null;
        } catch (NumberFormatException e) {
            log.debug("Unparseable Retry-After header from Brevo: {}", headerValue);
            return null;
        }
    }

    // -- mapping -----------------------------------------------------------------------------

    private BrevoWire.SendRequest toWireRequest(SendEmailCommand command) {
        List<BrevoWire.MessageVersion> versions = command.isMultiVariant()
                ? command.variants().stream().map(BrevoEmailProvider::toWireVersion).toList()
                : null;

        // Brevo requires a top-level `to` even when messageVersions is present, where it acts as
        // the default for versions that do not override it. If the caller supplied recipients
        // only inside variants, the first variant's recipients stand in.
        List<EmailAddress> topLevelTo = command.to();
        if (topLevelTo.isEmpty() && command.isMultiVariant()) {
            topLevelTo = command.variants().get(0).to();
        }

        return new BrevoWire.SendRequest(
                toContact(command.sender()),
                toContacts(topLevelTo),
                emptyToNull(toContacts(command.cc())),
                emptyToNull(toContacts(command.bcc())),
                toContact(command.replyTo()),
                command.subject(),
                blankToNull(command.htmlContent()),
                blankToNull(command.textContent()),
                versions);
    }

    private static BrevoWire.MessageVersion toWireVersion(MessageVariant variant) {
        return new BrevoWire.MessageVersion(
                toContacts(variant.to()),
                blankToNull(variant.subject()),
                blankToNull(variant.htmlContent()));
    }

    private static List<BrevoWire.Contact> toContacts(List<EmailAddress> addresses) {
        return addresses == null ? List.of() : addresses.stream().map(BrevoEmailProvider::toContact).toList();
    }

    private static BrevoWire.Contact toContact(EmailAddress address) {
        return address == null ? null : new BrevoWire.Contact(address.email(), address.name());
    }

    private static <T> List<T> emptyToNull(List<T> list) {
        return (list == null || list.isEmpty()) ? null : list;
    }

    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value;
    }

    private static String suffix(String providerCode) {
        return providerCode == null ? "" : " [code=" + providerCode + "]";
    }

    private static long elapsedMillis(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }
}
