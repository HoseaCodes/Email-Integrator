package com.hoseacodes.emailintegrator.service;

import com.hoseacodes.emailintegrator.config.EmailProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests that caller-supplied values cannot break out of the template.
 *
 * <p>These are the regression tests for ENGINEERING_AUDIT CRIT-2/HIGH-4. Before this work the
 * substituted values went in raw, and {@code /auth/send-email} was reachable anonymously — so a
 * stranger could have this service deliver arbitrary markup, from a real sending domain, to any
 * address they chose.
 */
class EmailTemplateServiceTest {

    private EmailTemplateService service;

    @BeforeEach
    void setUp() {
        service = new EmailTemplateService(new LinkSanitizer(new EmailProperties()));
    }

    private String renderPasswordReset(Map<String, String> text, Map<String, String> links) {
        return service.processTemplate("password-reset.html", text, links);
    }

    // -- escaping ------------------------------------------------------------------------------

    @Test
    @DisplayName("markup in a text value is escaped, not rendered")
    void escapesMarkupInTextValues() {
        String rendered = renderPasswordReset(
                Map.of("userName", "<script>alert('xss')</script>"),
                Map.of("resetUrl", "https://app.example.com/reset?t=abc"));

        assertThat(rendered).doesNotContain("<script>");
        assertThat(rendered).contains("&lt;script&gt;");
    }

    @Test
    @DisplayName("a value cannot close the attribute it is rendered inside")
    void escapesAttributeBreakout() {
        // The classic payload: close the quoted attribute, then add an event handler.
        String rendered = renderPasswordReset(
                Map.of("userName", "\"><img src=x onerror=alert(1)>"),
                Map.of("resetUrl", "https://app.example.com/reset"));

        // What matters is that no tag is created. The characters "onerror=alert(1)" still appear
        // as inert text — escaping neutralises the markup, it does not censor the words.
        assertThat(rendered).doesNotContain("<img");
        assertThat(rendered).contains("&quot;&gt;&lt;img src=x onerror=alert(1)&gt;");
    }

    @Test
    @DisplayName("a value containing another placeholder is not expanded")
    void doesNotExpandPlaceholdersInsideValues() {
        // Sequential per-variable replacement would expand this on a later pass, letting a
        // caller pull in a value they were never given access to.
        String rendered = renderPasswordReset(
                Map.of("userName", "{{resetUrl}}", "expiryTime", "1 hour"),
                Map.of("resetUrl", "https://app.example.com/secret-token-here"));

        // The placeholder survives as literal text in the name slot. Had substitution re-scanned
        // its own output, it would have been replaced by the URL and this would be absent.
        assertThat(rendered).contains("{{resetUrl}}");

        // password-reset.html renders resetUrl in two places (the button href and the visible
        // fallback link), so exactly two occurrences is correct — a third would mean the value
        // in userName had been expanded as well.
        assertThat(rendered.split("secret-token-here", -1).length - 1).isEqualTo(2);
    }

    @Test
    @DisplayName("a dollar sign in a value does not corrupt the output")
    void handlesDollarSignsInValues() {
        // Matcher.appendReplacement treats $1 as a group reference; without quoteReplacement
        // this throws or silently mangles the message.
        String rendered = renderPasswordReset(
                Map.of("userName", "Coupon $1 off $0 today"),
                Map.of("resetUrl", "https://app.example.com/reset"));

        assertThat(rendered).contains("Coupon $1 off $0 today");
    }

    @Test
    @DisplayName("a null text value renders as empty rather than the literal 'null'")
    void nullValuesRenderEmpty() {
        java.util.Map<String, String> text = new java.util.HashMap<>();
        text.put("userName", null);

        String rendered = renderPasswordReset(text, Map.of("resetUrl", "https://app.example.com/r"));

        assertThat(rendered).doesNotContain("null");
    }

    @Test
    @DisplayName("placeholders with no supplied value do not leak into the delivered email")
    void unresolvedPlaceholdersAreBlank() {
        String rendered = renderPasswordReset(
                Map.of("userName", "Alex"),
                Map.of("resetUrl", "https://app.example.com/reset"));

        assertThat(rendered).doesNotContain("{{");
        assertThat(rendered).doesNotContain("}}");
    }

    @Test
    @DisplayName("a legitimate value still renders correctly")
    void rendersNormalContent() {
        String rendered = renderPasswordReset(
                Map.of("userName", "Alex Smith", "expiryTime", "30 minutes"),
                Map.of("resetUrl", "https://app.example.com/reset?token=abc123"));

        assertThat(rendered).contains("Alex Smith");
        assertThat(rendered).contains("30 minutes");
        assertThat(rendered).contains("https://app.example.com/reset?token=abc123");
    }

    // -- link handling -------------------------------------------------------------------------

    @ParameterizedTest(name = "{0} is refused in an href")
    @ValueSource(strings = {
            "javascript:alert(document.domain)",
            "JavaScript:alert(1)",
            "data:text/html;base64,PHNjcmlwdD5hbGVydCgxKTwvc2NyaXB0Pg==",
            "vbscript:msgbox(1)",
            "file:///etc/passwd"
    })
    @DisplayName("dangerous URL schemes are refused outright")
    void refusesDangerousSchemes(String url) {
        // HTML-escaping alone would leave every one of these intact and clickable.
        assertThatThrownBy(() -> renderPasswordReset(Map.of("userName", "Alex"), Map.of("resetUrl", url)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("resetUrl");
    }

    @Test
    @DisplayName("a relative URL is refused")
    void refusesRelativeUrl() {
        assertThatThrownBy(() -> renderPasswordReset(
                Map.of("userName", "Alex"), Map.of("resetUrl", "/reset?token=abc")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("an empty link renders as empty rather than failing the send")
    void emptyLinkIsAllowed() {
        String rendered = renderPasswordReset(Map.of("userName", "Alex"), Map.of("resetUrl", ""));

        assertThat(rendered).contains("Alex");
    }

    @Test
    @DisplayName("when a host allowlist is configured, off-list links are refused")
    void enforcesHostAllowlist() {
        EmailProperties properties = new EmailProperties();
        properties.setAllowedLinkHosts(List.of("app.example.com"));
        EmailTemplateService restricted = new EmailTemplateService(new LinkSanitizer(properties));

        // An authenticated but misbehaving client aiming a reset link at a host it controls.
        assertThatThrownBy(() -> restricted.processTemplate("password-reset.html",
                Map.of("userName", "Alex"),
                Map.of("resetUrl", "https://attacker.example/harvest")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not permitted");

        String allowed = restricted.processTemplate("password-reset.html",
                Map.of("userName", "Alex"),
                Map.of("resetUrl", "https://app.example.com/reset"));
        assertThat(allowed).contains("https://app.example.com/reset");
    }

    @Test
    @DisplayName("a lookalike host is not accepted as the allowlisted one")
    void allowlistIsNotSubstringMatched() {
        EmailProperties properties = new EmailProperties();
        properties.setAllowedLinkHosts(List.of("example.com"));
        EmailTemplateService restricted = new EmailTemplateService(new LinkSanitizer(properties));

        // A substring check would accept both of these; host comparison must be exact.
        assertThatThrownBy(() -> restricted.processTemplate("password-reset.html",
                Map.of("userName", "Alex"), Map.of("resetUrl", "https://example.com.attacker.net/x")))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> restricted.processTemplate("password-reset.html",
                Map.of("userName", "Alex"), Map.of("resetUrl", "https://notexample.com/x")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a link is validated before the template is even loaded")
    void invalidLinkFailsEvenForAnUnknownTemplate() {
        assertThatThrownBy(() -> service.processTemplate("no-such-template.html",
                Map.of(), Map.of("resetUrl", "javascript:alert(1)")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a missing template falls back to the built-in one, still escaped")
    void fallsBackSafely() {
        String rendered = service.processTemplate("account-approved.html",
                Map.of("userName", "<b>bold</b>"),
                Map.of("loginUrl", "https://app.example.com/login"));

        assertThat(rendered).doesNotContain("<b>bold</b>");
        assertThat(rendered).contains("&lt;b&gt;bold&lt;/b&gt;");
    }
}
