package com.hoseacodes.emailintegrator.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;

/**
 * Authenticates a request by its API key header.
 *
 * <p>Populates the {@link SecurityContextHolder} when a valid key is presented and otherwise
 * does nothing — it never writes a response itself. Rejection is left to the filter chain, so
 * the "who is allowed where" decision lives in one place ({@link SecurityConfig}) rather than
 * being split between a filter and a config class.
 */
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    static final String ROLE_API_CLIENT = "ROLE_API_CLIENT";

    private static final Logger log = LoggerFactory.getLogger(ApiKeyAuthenticationFilter.class);

    private final ApiKeyProperties properties;

    public ApiKeyAuthenticationFilter(ApiKeyProperties properties) {
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String presentedKey = request.getHeader(properties.headerName());

        if (presentedKey != null && !presentedKey.isBlank()) {
            String clientId = resolveClient(presentedKey);

            if (clientId != null) {
                UsernamePasswordAuthenticationToken authentication =
                        new UsernamePasswordAuthenticationToken(
                                clientId,
                                null, // credentials are cleared immediately; never held in context
                                List.of(new SimpleGrantedAuthority(ROLE_API_CLIENT)));
                authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(authentication);

                log.debug("Authenticated client '{}' for {} {}",
                        clientId, request.getMethod(), request.getRequestURI());
            } else {
                // Warn, not error: a single bad key is routine. It is logged so a burst of them
                // is visible as the credential-guessing attempt it probably is.
                // The presented key is never logged — an attacker's near-miss guess is still a
                // secret, and a mistyped valid key would otherwise land in plaintext in the log.
                log.warn("Rejected invalid API key for {} {}",
                        request.getMethod(), request.getRequestURI());
            }
        }

        filterChain.doFilter(request, response);
    }

    /**
     * Finds the client owning the presented key, or null.
     *
     * <p>Two deliberate details:
     *
     * <ul>
     *   <li>{@link MessageDigest#isEqual} rather than {@code String.equals}. String comparison
     *       returns as soon as it finds a differing character, so response time leaks how many
     *       leading characters were correct — enough, over many requests, to reconstruct a key
     *       one character at a time. {@code isEqual} is documented as time-constant.</li>
     *   <li>The loop does not break on a match. Returning early would make response time depend
     *       on the matched key's position in the map, reintroducing a smaller version of the
     *       same leak.</li>
     * </ul>
     */
    private String resolveClient(String presentedKey) {
        byte[] presented = presentedKey.getBytes(StandardCharsets.UTF_8);
        String matchedClient = null;

        for (Map.Entry<String, String> entry : properties.apiKeys().entrySet()) {
            byte[] configured = entry.getValue().getBytes(StandardCharsets.UTF_8);
            if (MessageDigest.isEqual(presented, configured)) {
                matchedClient = entry.getKey();
            }
        }
        return matchedClient;
    }
}
