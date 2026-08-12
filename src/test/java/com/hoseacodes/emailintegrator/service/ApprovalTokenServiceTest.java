package com.hoseacodes.emailintegrator.service;

import com.hoseacodes.emailintegrator.config.JwtProperties;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Date;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for approval-link token issuance and verification.
 *
 * <p>The negative cases carry the weight here. A token service that mints and reads back its own
 * tokens proves almost nothing — the security property is what it <em>refuses</em>: forged
 * signatures, tokens signed with a different key, expired tokens, and tokens of the wrong type
 * replayed at the approval endpoint.
 */
class ApprovalTokenServiceTest {

    private static final String SECRET = "test-secret-key-that-is-at-least-256-bits-long-for-testing";
    private static final String ISSUER = "email-integrator-test";

    private ApprovalTokenService service;

    @BeforeEach
    void setUp() {
        service = new ApprovalTokenService(
                new JwtProperties(SECRET, Duration.ofHours(24), ISSUER));
    }

    private static SecretKey keyFor(String secret) {
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    // -- issuance and round trip ---------------------------------------------------------------

    @Test
    @DisplayName("a freshly issued token verifies and carries its claims back")
    void roundTrip() {
        String token = service.generateApprovalToken("user@example.com", "Alex Smith");

        Map<String, Object> claims = service.verifyApprovalTokenWithClaims(token);

        assertThat(claims).isNotNull();
        assertThat(claims.get("email")).isEqualTo("user@example.com");
        assertThat(claims.get("name")).isEqualTo("Alex Smith");
        assertThat(claims.get("subject")).isEqualTo("user@example.com");
    }

    @Test
    @DisplayName("the name is optional")
    void roundTripWithoutName() {
        String token = service.generateApprovalToken("user@example.com");

        Map<String, Object> claims = service.verifyApprovalTokenWithClaims(token);

        assertThat(claims).isNotNull();
        assertThat(claims.get("email")).isEqualTo("user@example.com");
        assertThat(claims.get("name")).isNull();
    }

    @Test
    @DisplayName("the convenience accessor returns the email address")
    void verifyReturnsEmail() {
        String token = service.generateApprovalToken("user@example.com", "Alex");

        assertThat(service.verifyApprovalToken(token)).isEqualTo("user@example.com");
    }

    @Test
    @DisplayName("tokens are opaque to inspection but not encrypted — claims are readable")
    void payloadIsSignedNotEncrypted() {
        String token = service.generateApprovalToken("user@example.com", "Alex");
        String payload = new String(Base64.getUrlDecoder()
                .decode(token.split("\\.")[1]), StandardCharsets.UTF_8);

        // Worth asserting explicitly so nobody later assumes a JWT hides anything. It guarantees
        // integrity, not confidentiality — never put a secret in one.
        assertThat(payload).contains("user@example.com");
    }

    // -- rejection -----------------------------------------------------------------------------

    @Nested
    @DisplayName("tokens that must be refused")
    class Rejections {

        @Test
        @DisplayName("a tampered payload is refused")
        void tamperedPayloadRefused() {
            String token = service.generateApprovalToken("user@example.com", "Alex");
            String[] parts = token.split("\\.");

            // Swap in a payload naming a different address, keeping the original signature.
            String forgedPayload = Base64.getUrlEncoder().withoutPadding().encodeToString(
                    ("{\"email\":\"attacker@evil.example\",\"type\":\"approval\",\"iss\":\"" + ISSUER + "\"}")
                            .getBytes(StandardCharsets.UTF_8));
            String forged = parts[0] + "." + forgedPayload + "." + parts[2];

            assertThat(service.verifyApprovalTokenWithClaims(forged)).isNull();
        }

        @Test
        @DisplayName("a token signed with a different key is refused")
        void wrongSigningKeyRefused() {
            // Exactly what an attacker who guessed the old published default would produce.
            String foreign = Jwts.builder()
                    .claims(Map.of("email", "attacker@evil.example", "type", "approval"))
                    .subject("attacker@evil.example")
                    .issuer(ISSUER)
                    .issuedAt(new Date())
                    .expiration(new Date(System.currentTimeMillis() + 3_600_000))
                    .signWith(keyFor("a-completely-different-signing-key-of-sufficient-length"))
                    .compact();

            assertThat(service.verifyApprovalTokenWithClaims(foreign)).isNull();
        }

        @Test
        @DisplayName("an expired token is refused")
        void expiredTokenRefused() {
            ApprovalTokenService shortLived = new ApprovalTokenService(
                    new JwtProperties(SECRET, Duration.ofMillis(1), ISSUER));

            String token = shortLived.generateApprovalToken("user@example.com", "Alex");

            // Mint with a 1ms lifetime, then verify with the normal service: already past expiry.
            assertThat(service.verifyApprovalTokenWithClaims(token)).isNull();
        }

        @Test
        @DisplayName("a token from a different issuer is refused")
        void wrongIssuerRefused() {
            // The realistic scenario: staging and production accidentally share a signing key.
            // Without issuer validation a staging approval link would work against production.
            String otherEnvironment = Jwts.builder()
                    .claims(Map.of("email", "user@example.com", "type", "approval"))
                    .subject("user@example.com")
                    .issuer("some-other-service")
                    .issuedAt(new Date())
                    .expiration(new Date(System.currentTimeMillis() + 3_600_000))
                    .signWith(keyFor(SECRET))
                    .compact();

            assertThat(service.verifyApprovalTokenWithClaims(otherEnvironment)).isNull();
        }

        @Test
        @DisplayName("a correctly signed token of the wrong type is refused")
        void wrongTokenTypeRefused() {
            // Signature and issuer are valid — only the type claim differs. This is why the type
            // check exists: it stops a token minted for another purpose being replayed here.
            String wrongType = Jwts.builder()
                    .claims(Map.of("email", "user@example.com", "type", "password-reset"))
                    .subject("user@example.com")
                    .issuer(ISSUER)
                    .issuedAt(new Date())
                    .expiration(new Date(System.currentTimeMillis() + 3_600_000))
                    .signWith(keyFor(SECRET))
                    .compact();

            assertThat(service.verifyApprovalTokenWithClaims(wrongType)).isNull();
        }

        @Test
        @DisplayName("a token with no type claim is refused")
        void missingTypeRefused() {
            String noType = Jwts.builder()
                    .claims(Map.of("email", "user@example.com"))
                    .subject("user@example.com")
                    .issuer(ISSUER)
                    .issuedAt(new Date())
                    .expiration(new Date(System.currentTimeMillis() + 3_600_000))
                    .signWith(keyFor(SECRET))
                    .compact();

            assertThat(service.verifyApprovalTokenWithClaims(noType)).isNull();
        }

        @Test
        @DisplayName("an unsigned 'alg: none' token is refused")
        void unsignedTokenRefused() {
            // The classic JWT attack: strip the signature and declare the algorithm as none.
            // JJWT's parseSignedClaims refuses unsigned tokens outright.
            String header = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8));
            String payload = Base64.getUrlEncoder().withoutPadding().encodeToString(
                    ("{\"email\":\"attacker@evil.example\",\"type\":\"approval\",\"iss\":\"" + ISSUER + "\"}")
                            .getBytes(StandardCharsets.UTF_8));

            assertThat(service.verifyApprovalTokenWithClaims(header + "." + payload + ".")).isNull();
        }

        @ParameterizedTest(name = "\"{0}\" is refused")
        @ValueSource(strings = {"", "   ", "not-a-jwt", "a.b.c", "....", "Bearer sometoken"})
        @DisplayName("malformed input is refused without throwing")
        void malformedTokensRefused(String token) {
            assertThat(service.verifyApprovalTokenWithClaims(token)).isNull();
        }

        @Test
        @DisplayName("a null token is refused without throwing")
        void nullTokenRefused() {
            assertThat(service.verifyApprovalTokenWithClaims(null)).isNull();
            assertThat(service.verifyApprovalToken(null)).isNull();
        }
    }

    // -- configuration -------------------------------------------------------------------------

    @Test
    @DisplayName("a signing key shorter than 256 bits is refused at construction, not first use")
    void weakKeyRejectedAtStartup() {
        // HS256 requires 256 bits. Deriving the key in the constructor turns this into a failed
        // deployment rather than a 500 on the first approval email someone tries to send.
        assertThatThrownBy(() -> new ApprovalTokenService(
                new JwtProperties("too-short", Duration.ofHours(24), ISSUER)))
                .isInstanceOf(io.jsonwebtoken.security.WeakKeyException.class);
    }

    @Test
    @DisplayName("the issuer defaults rather than being silently blank")
    void issuerDefaults() {
        JwtProperties properties = new JwtProperties(SECRET, Duration.ofHours(24), "  ");

        assertThat(properties.issuer()).isEqualTo("email-integrator");
    }
}
