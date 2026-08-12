package com.hoseacodes.emailintegrator.brevo;

import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.util.Timeout;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Builds the HTTP client used to reach Brevo.
 *
 * <h2>Why timeouts are set here and not left to defaults</h2>
 * A bare {@code new RestTemplate()} — what this codebase used previously — has <em>no</em>
 * connect or read timeout. A call against an unresponsive provider blocks its Tomcat worker
 * indefinitely. Under sustained slowness the worker pool drains and the service stops answering
 * every endpoint, including {@code /actuator/health}, at which point the platform health check
 * fails and the instance is replaced mid-flight.
 *
 * <p>A <em>slow</em> dependency is more dangerous than a <em>down</em> one: a down dependency
 * fails fast and releases the thread, while a slow one holds resources. Timeouts convert the
 * slow case into the fast case.
 *
 * <h2>Why automatic retries are disabled explicitly</h2>
 * This is the part that is easy to miss and expensive to get wrong. Apache HttpClient 5 installs
 * a retry strategy <em>by default</em>: it re-sends on assorted I/O failures, and on HTTP 429 and
 * 503 it will wait out the provider's {@code Retry-After} and try again. None of that is visible
 * in application code.
 *
 * <p>Sending email is not idempotent. A retried POST is a second message in someone's inbox. A
 * default buried in a transitive dependency must not be allowed to make that decision — so
 * retries are turned off here, deliberately and visibly, and a test asserts exactly one request
 * is issued per send.
 *
 * <p>This is also why {@code httpclient5} is a declared dependency rather than an inherited one.
 * {@code ClientHttpRequestFactories.get()} picks whichever client is on the classpath, which
 * meant the outbound retry policy was previously being decided by Spring Cloud Vault's
 * dependency tree. Retry semantics are too important to inherit by accident.
 *
 * <p>Retries are not gone forever — they are gone until they can be made safe. See
 * {@code docs/RELIABILITY.md} for the conditions under which a retry could be reintroduced
 * (a {@code CONNECT_FAILED} classification, or an idempotency key the provider honours).
 */
@Configuration
@EnableConfigurationProperties(BrevoProperties.class)
public class BrevoClientConfig {

    /**
     * @return a {@link RestClient} bound to Brevo's base URL, with explicit timeouts and
     *         automatic retries disabled. The API key is not attached here — it is set per
     *         request by {@link BrevoEmailProvider}, keeping the credential out of shared
     *         client state.
     */
    @Bean
    RestClient brevoRestClient(BrevoProperties properties) {
        // connect timeout: giving up on establishing the TCP connection.
        // socket timeout: the low-level read timeout on an established connection.
        ConnectionConfig connectionConfig = ConnectionConfig.custom()
                .setConnectTimeout(Timeout.of(properties.connectTimeout()))
                .setSocketTimeout(Timeout.of(properties.readTimeout()))
                .build();

        PoolingHttpClientConnectionManager connectionManager =
                PoolingHttpClientConnectionManagerBuilder.create()
                        .setDefaultConnectionConfig(connectionConfig)
                        .build();

        // responseTimeout: how long to wait for the response head after the request is written.
        RequestConfig requestConfig = RequestConfig.custom()
                .setResponseTimeout(Timeout.of(properties.readTimeout()))
                .setConnectionRequestTimeout(Timeout.of(properties.connectTimeout()))
                .build();

        CloseableHttpClient httpClient = HttpClients.custom()
                .setConnectionManager(connectionManager)
                .setDefaultRequestConfig(requestConfig)
                // The critical line. Without it, HttpClient re-sends non-idempotent email
                // sends on 429/503 and on transient I/O errors, producing duplicate messages.
                .disableAutomaticRetries()
                // Following redirects on an API POST would silently re-issue the send against
                // a location we did not choose. Nothing legitimate needs it.
                .disableRedirectHandling()
                .build();

        return RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(new HttpComponentsClientHttpRequestFactory(httpClient))
                .defaultHeader("accept", MediaType.APPLICATION_JSON_VALUE)
                .build();
    }
}
