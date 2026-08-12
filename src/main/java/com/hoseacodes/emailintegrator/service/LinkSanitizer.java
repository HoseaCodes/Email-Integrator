package com.hoseacodes.emailintegrator.service;

import com.hoseacodes.emailintegrator.config.EmailProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Validates URLs before they are placed into an outgoing email's {@code href}.
 *
 * <h2>Why this exists</h2>
 * Five template variables — {@code approvalUrl}, {@code denyUrl}, {@code loginUrl},
 * {@code resetUrl}, {@code meetingLink} — are supplied by the API caller and land directly in an
 * anchor's {@code href}. HTML-escaping alone does not make that safe: {@code javascript:...} and
 * {@code data:text/html;base64,...} are perfectly well-formed attribute values that escaping
 * leaves entirely intact. A URL in an {@code href} is a distinct security context from text in a
 * paragraph, and needs its own rule.
 *
 * <h2>Two layers, deliberately</h2>
 * <ol>
 *   <li><b>Scheme allowlist (always on).</b> Only {@code http} and {@code https}. This closes the
 *       script-execution class outright and is not configurable, because there is no legitimate
 *       reason for this service to emit any other scheme.</li>
 *   <li><b>Host allowlist (optional).</b> When {@code app.email.allowed-link-hosts} is set, links
 *       must point at one of those hosts. This is what stops an <em>authenticated but
 *       misbehaving</em> client from sending a password-reset email, from this domain, pointing
 *       at a host it controls.</li>
 * </ol>
 *
 * <p>The host allowlist is opt-in rather than mandatory, and that is a real trade-off worth
 * being explicit about. Now that every sending endpoint requires an API key, the primary control
 * is authentication; the allowlist is defence in depth for a compromised or careless client.
 * Leaving it unset is safe against anonymous abuse but not against a leaked key — so it should be
 * set in any deployment that matters. A warning is logged at startup when it is empty rather
 * than letting the gap pass silently.
 *
 * <p>Invalid links are rejected with an exception rather than quietly blanked. A caller who sent
 * a bad URL gets a 400 and can fix it; silently mailing someone a dead button helps nobody.
 */
@Component
public class LinkSanitizer {

    private static final Logger log = LoggerFactory.getLogger(LinkSanitizer.class);

    /** Not configurable. Anything outside this set is a script-execution or data-exfiltration vector. */
    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");

    private final List<String> allowedHosts;

    public LinkSanitizer(EmailProperties emailProperties) {
        this.allowedHosts = emailProperties.getAllowedLinkHosts() == null
                ? List.of()
                : emailProperties.getAllowedLinkHosts().stream()
                        .filter(host -> host != null && !host.isBlank())
                        .map(host -> host.trim().toLowerCase(Locale.ROOT))
                        .toList();

        if (allowedHosts.isEmpty()) {
            log.warn("app.email.allowed-link-hosts is not configured. Caller-supplied links may "
                    + "point at any http(s) host. Set it to restrict outgoing links to known domains.");
        } else {
            log.info("Outgoing email links restricted to hosts: {}", allowedHosts);
        }
    }

    /**
     * Returns the URL if it is safe to place in an {@code href}, or throws.
     *
     * @param url caller-supplied link; null or blank is allowed and yields an empty string, so a
     *            template with an optional link renders without a broken anchor
     * @throws IllegalArgumentException if the URL is malformed, relative, uses a disallowed
     *         scheme, or points outside the configured host allowlist
     */
    public String sanitize(String fieldName, String url) {
        if (url == null || url.isBlank()) {
            return "";
        }

        URI uri;
        try {
            uri = new URI(url.trim());
        } catch (URISyntaxException e) {
            throw reject(fieldName, "is not a valid URL");
        }

        if (!uri.isAbsolute() || uri.getScheme() == null) {
            throw reject(fieldName, "must be an absolute URL including a scheme");
        }

        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        if (!ALLOWED_SCHEMES.contains(scheme)) {
            // Logged so an attempt to inject javascript:/data: is visible, not just refused.
            log.warn("Rejected disallowed URL scheme '{}' for link field '{}'", scheme, fieldName);
            throw reject(fieldName, "must use http or https");
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw reject(fieldName, "must include a host");
        }

        if (!allowedHosts.isEmpty() && !allowedHosts.contains(host.toLowerCase(Locale.ROOT))) {
            log.warn("Rejected link to non-allowlisted host '{}' for field '{}'", host, fieldName);
            throw reject(fieldName, "points to a host that is not permitted for outgoing links");
        }

        return uri.toString();
    }

    private static IllegalArgumentException reject(String fieldName, String problem) {
        // The message names the field and the rule but never echoes the offending value, which
        // would put attacker-controlled text into an API response.
        return new IllegalArgumentException(fieldName + " " + problem);
    }
}
