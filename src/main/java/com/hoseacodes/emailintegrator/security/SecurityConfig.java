package com.hoseacodes.emailintegrator.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hoseacodes.emailintegrator.controller.ApiError;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.util.UUID;

/**
 * The application's security boundary.
 *
 * <p>Every rule is stated here, in one place, so "what is public?" is answered by reading one
 * method rather than by auditing annotations across three controllers.
 *
 * <h2>Deny by default</h2>
 * {@code anyRequest().authenticated()} is the last rule, so a new endpoint is protected the
 * moment it is written. The opposite arrangement — permit by default, protect explicitly — fails
 * open, and the endpoint someone forgets to list is exactly the one that matters.
 *
 * <h2>The three deliberate exceptions</h2>
 * <ul>
 *   <li><b>{@code GET /actuator/health}</b> — the load balancer or platform health check cannot
 *       present credentials. It exposes only {@code {"status":"UP"}}; details remain gated.</li>
 *   <li><b>{@code GET /auth/approve} and {@code GET /auth/deny}</b> — these are links a human
 *       clicks in an email client, which cannot attach an API key. They are <em>not</em>
 *       unauthenticated: each carries a signed, expiring JWT that the controller verifies, and
 *       that token is the credential. This is authorisation of a single action rather than
 *       authentication of a caller, and it is why the JWT machinery in
 *       {@code ApprovalTokenService} exists.</li>
 *   <li><b>OpenAPI documentation</b> — the schema describes the contract, not any data, and a
 *       reviewer being able to read it without credentials is the point of publishing it. Worth
 *       restricting in a genuinely production deployment; see {@code docs/SECURITY.md}.</li>
 * </ul>
 *
 * <h2>Why CSRF protection is disabled</h2>
 * Not for convenience. CSRF exploits <em>ambient</em> credentials — cookies the browser attaches
 * automatically. This API is stateless, issues no cookies, and authenticates via a header a
 * browser will never add on its own, so a cross-site form post arrives unauthenticated and is
 * rejected. Leaving CSRF enabled would require token round-trips that protect nothing here. If
 * cookie-based sessions are ever introduced, this must be reconsidered.
 */
@Configuration
@EnableWebSecurity
@EnableConfigurationProperties(ApiKeyProperties.class)
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http,
                                            ApiKeyProperties apiKeyProperties,
                                            AuthenticationEntryPoint authenticationEntryPoint,
                                            AccessDeniedHandler accessDeniedHandler) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers(HttpMethod.GET, "/actuator/health").permitAll()
                        // Signed-token links clicked from an email client.
                        .requestMatchers(HttpMethod.GET, "/auth/approve", "/auth/deny").permitAll()
                        .requestMatchers(HttpMethod.GET,
                                "/swagger-ui.html", "/swagger-ui/**",
                                "/v3/api-docs", "/v3/api-docs/**").permitAll()
                        .anyRequest().authenticated())
                .addFilterBefore(new ApiKeyAuthenticationFilter(apiKeyProperties),
                        UsernamePasswordAuthenticationFilter.class)
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                // No browser login surface exists; leaving these enabled would advertise one.
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .logout(logout -> logout.disable())
                // Spring Security's defaults (nosniff, frame-options DENY, cache-control) are
                // kept deliberately rather than switched off.
                .headers(Customizer.withDefaults());

        log.info("Security enabled: {} API client key(s) configured, header '{}'",
                apiKeyProperties.apiKeys().size(), apiKeyProperties.headerName());

        return http.build();
    }

    /**
     * Returns 401 in the same {@link ApiError} shape as every other failure.
     *
     * <p>Spring Security's default would emit an HTML error page or an empty body, so a client
     * parsing errors generically would break precisely when authentication fails. The message
     * does not distinguish "no key supplied" from "wrong key" — that difference is useful only
     * to someone probing for valid credentials.
     */
    @Bean
    AuthenticationEntryPoint restAuthenticationEntryPoint(ObjectMapper objectMapper,
                                                          ApiKeyProperties properties) {
        return (request, response, authException) -> {
            String errorId = shortId();
            log.warn("Unauthenticated request [{}] to {} {}",
                    errorId, request.getMethod(), request.getRequestURI());

            writeError(objectMapper, response, ApiError.of(
                    HttpStatus.UNAUTHORIZED.value(),
                    "UNAUTHENTICATED",
                    "A valid " + properties.headerName() + " header is required.",
                    request.getRequestURI(),
                    errorId));
        };
    }

    /** Returns 403 when a caller is authenticated but lacks the authority for this endpoint. */
    @Bean
    AccessDeniedHandler restAccessDeniedHandler(ObjectMapper objectMapper) {
        return (request, response, accessDeniedException) -> {
            String errorId = shortId();
            log.warn("Access denied [{}] for {} {}",
                    errorId, request.getMethod(), request.getRequestURI());

            writeError(objectMapper, response, ApiError.of(
                    HttpStatus.FORBIDDEN.value(),
                    "FORBIDDEN",
                    "This client is not permitted to perform that operation.",
                    request.getRequestURI(),
                    errorId));
        };
    }

    private static void writeError(ObjectMapper objectMapper,
                                   HttpServletResponse response,
                                   ApiError body) throws java.io.IOException {
        response.setStatus(body.status());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getOutputStream(), body);
    }

    private static String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
