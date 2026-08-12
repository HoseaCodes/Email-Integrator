package com.hoseacodes.emailintegrator.service;

import com.hoseacodes.emailintegrator.config.JwtProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.security.SignatureException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * Issues and verifies the signed links emailed to an administrator for approving or denying a
 * registration.
 *
 * <h2>What these tokens are, and are not</h2>
 * This is <em>not</em> the API's authentication mechanism — that is the API key checked by
 * {@code ApiKeyAuthenticationFilter}. A token here is a <b>capability</b>: a single, expiring,
 * tamper-evident permission to perform one action, handed to a human who will click it in a mail
 * client that cannot attach headers. Conflating the two is a common source of confusion; keeping
 * them separate is why {@code GET /auth/approve} can be permitted in the security config without
 * being unauthenticated.
 *
 * <h2>What is verified</h2>
 * <ol>
 *   <li><b>Signature</b> — proves the token was minted with our key and not altered. A tampered
 *       payload fails here, so claims can be trusted only after this passes.</li>
 *   <li><b>Expiry</b> — enforced by JJWT during parsing.</li>
 *   <li><b>Issuer</b> — required to match. With one issuer today this guards a specific mistake:
 *       two environments accidentally sharing a signing key, where a staging token would
 *       otherwise be accepted in production.</li>
 *   <li><b>Token type</b> — a custom {@code type} claim, checked after the signature. This stops
 *       a token minted for some future purpose being replayed against the approval endpoint.</li>
 * </ol>
 *
 * <p>No audience claim. {@code aud} distinguishes multiple intended recipients of a token, and
 * there is exactly one consumer — this service. Adding it would be ceremony without a threat it
 * addresses.
 *
 * <h2>Revocation</h2>
 * These tokens are stateless, so nothing can withdraw one before it expires; the 24-hour
 * lifetime is the entire containment window. Shortening that is the cheapest lever. Real
 * revocation needs server-side state — the standard approach is a {@code jti} claim plus a store
 * of consumed or revoked ids, checked on every verification. That would also make these links
 * single-use, which they currently are not: an email-client link prefetcher can trigger an
 * approval simply by fetching the URL (ENGINEERING_AUDIT MED-6). Deliberately not implemented
 * here — a token store is a real design decision, not a line of code, and it belongs with the
 * idempotency work rather than bolted on.
 */
@Service
@EnableConfigurationProperties(JwtProperties.class)
public class ApprovalTokenService {

    private static final String TOKEN_TYPE_APPROVAL = "approval";

    private static final Logger log = LoggerFactory.getLogger(ApprovalTokenService.class);

    private final JwtProperties properties;
    private final SecretKey signingKey;

    public ApprovalTokenService(JwtProperties properties) {
        this.properties = properties;
        // Derived once, at startup. Besides avoiding the per-call cost, this means a key that is
        // structurally unusable fails the deployment rather than the first approval email.
        this.signingKey = Keys.hmacShaKeyFor(properties.secret().getBytes(StandardCharsets.UTF_8));
    }

    /** Generates an approval token carrying only the email address. */
    public String generateApprovalToken(String email) {
        return generateApprovalToken(email, null);
    }

    /** Generates an approval token carrying the email address and display name. */
    public String generateApprovalToken(String email, String name) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("email", email);
        if (name != null) {
            claims.put("name", name);
        }
        claims.put("type", TOKEN_TYPE_APPROVAL);

        Date issuedAt = new Date();
        Date expiresAt = new Date(issuedAt.getTime() + properties.expiration().toMillis());

        return Jwts.builder()
                .claims(claims)
                .subject(email)
                .issuer(properties.issuer())
                .issuedAt(issuedAt)
                .expiration(expiresAt)
                .signWith(signingKey)
                .compact();
    }

    /**
     * Verifies a token and returns its claims, or null if it cannot be trusted.
     *
     * <p>Every failure returns null and the caller reports one generic message. That is
     * deliberate: telling the presenter whether a token was expired, forged, or of the wrong type
     * is useful mainly to someone probing the endpoint. The distinction is recorded in the log
     * instead, where it is available for diagnosis.
     *
     * @param token the compact JWT from the link's query string
     * @return the token's claims, or null if it is missing, malformed, expired, tampered with,
     *         issued elsewhere, or not an approval token
     */
    public Map<String, Object> verifyApprovalTokenWithClaims(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }

        Claims claims;
        try {
            claims = Jwts.parser()
                    .verifyWith(signingKey)
                    .requireIssuer(properties.issuer())
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

        } catch (ExpiredJwtException e) {
            // Routine: approval links are meant to age out.
            log.info("Approval token rejected: expired at {}", e.getClaims().getExpiration());
            return null;
        } catch (SignatureException e) {
            // Not routine. A bad signature means someone altered a token or guessed at our key.
            log.warn("Approval token rejected: signature verification failed");
            return null;
        } catch (JwtException | IllegalArgumentException e) {
            // Malformed, wrong issuer, unsupported algorithm, structurally invalid.
            log.warn("Approval token rejected: {}", e.getClass().getSimpleName());
            return null;
        }

        // Checked only after the signature is verified — claims from an unverified token are
        // attacker-controlled input and must not influence any decision.
        String tokenType = claims.get("type", String.class);
        if (!TOKEN_TYPE_APPROVAL.equals(tokenType)) {
            log.warn("Approval token rejected: wrong token type '{}'", tokenType);
            return null;
        }

        Map<String, Object> result = new HashMap<>();
        result.put("email", claims.get("email", String.class));
        result.put("name", claims.get("name", String.class));
        result.put("subject", claims.getSubject());
        result.put("issuedAt", claims.getIssuedAt());
        result.put("expiration", claims.getExpiration());
        return result;
    }

    /**
     * Verifies a token and returns just the email address, or null.
     *
     * @see #verifyApprovalTokenWithClaims(String)
     */
    public String verifyApprovalToken(String token) {
        Map<String, Object> claims = verifyApprovalTokenWithClaims(token);
        return claims == null ? null : (String) claims.get("email");
    }
}
