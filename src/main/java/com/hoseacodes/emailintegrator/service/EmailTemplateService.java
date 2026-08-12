package com.hoseacodes.emailintegrator.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;
import org.springframework.web.util.HtmlUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Renders HTML email templates by substituting {@code {{variable}}} placeholders.
 *
 * <h2>Output encoding</h2>
 * Every substituted value is HTML-escaped. Previously they were inserted raw via
 * {@code String.replace}, so a caller-supplied value such as {@code "><script>...} closed the
 * surrounding attribute and injected arbitrary markup — and because {@code /auth/send-email}
 * required no authentication, any stranger could aim that at any recipient, from a real domain.
 * See ENGINEERING_AUDIT CRIT-2 and HIGH-4.
 *
 * <p>Escaping is not uniform, because HTML is not one context. Values are supplied in two
 * groups:
 *
 * <ul>
 *   <li><b>text</b> — HTML-escaped, safe for element content and quoted attributes;</li>
 *   <li><b>links</b> — passed through {@link LinkSanitizer} <em>first</em> (scheme and host
 *       rules), then HTML-escaped. Escaping alone would happily preserve
 *       {@code javascript:alert(1)} as a valid {@code href}.</li>
 * </ul>
 *
 * <p>Callers name which group each value belongs to, rather than this class guessing from the
 * variable name. A guess silently fails open the day someone adds a new URL field.
 *
 * <h2>Two subtler injection paths, also closed</h2>
 * <ol>
 *   <li><b>Sequential replacement was itself injectable.</b> Replacing placeholders one variable
 *       at a time re-scans text already substituted, so a value containing the literal
 *       {@code {{approvalUrl}}} would be expanded on a later pass. Substitution is now a single
 *       regex pass over the template; substituted content is never re-examined.</li>
 *   <li><b>{@code $} in values.</b> {@link Matcher#appendReplacement} treats {@code $1} as a
 *       group reference, so a value containing {@code $} could corrupt output or throw at
 *       runtime. {@link Matcher#quoteReplacement} neutralises it.</li>
 * </ol>
 *
 * <h2>Why not Thymeleaf</h2>
 * Thymeleaf would give contextual escaping by default and is the better long-term answer — the
 * README already (incorrectly) claims it. It is deliberately not part of this change: swapping
 * the template engine and closing an injection hole at once would make it impossible to tell
 * which change fixed what if something regressed. Recorded as follow-up work.
 */
@Service
public class EmailTemplateService {

    private static final Logger log = LoggerFactory.getLogger(EmailTemplateService.class);

    /** Matches {@code {{name}}}. Restricted to word characters so it cannot span markup. */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{(\\w+)}}");

    private final LinkSanitizer linkSanitizer;

    public EmailTemplateService(LinkSanitizer linkSanitizer) {
        this.linkSanitizer = linkSanitizer;
    }

    /**
     * Loads a template and substitutes escaped values.
     *
     * @param templateName  file name under {@code resources/templates}
     * @param textVariables values rendered as text; HTML-escaped
     * @param linkVariables values rendered inside {@code href}; validated then HTML-escaped
     * @return the rendered HTML
     * @throws IllegalArgumentException if a link value fails {@link LinkSanitizer}'s rules
     */
    public String processTemplate(String templateName,
                                  Map<String, String> textVariables,
                                  Map<String, String> linkVariables) {

        // Link validation happens before the template is loaded, so a bad link fails fast and
        // identically whether or not the template file is present.
        Map<String, String> safeValues = new HashMap<>();

        if (linkVariables != null) {
            linkVariables.forEach((name, value) ->
                    safeValues.put(name, HtmlUtils.htmlEscape(linkSanitizer.sanitize(name, value))));
        }
        if (textVariables != null) {
            textVariables.forEach((name, value) ->
                    safeValues.put(name, HtmlUtils.htmlEscape(value == null ? "" : value)));
        }

        String template;
        try {
            template = loadTemplate(templateName);
        } catch (IOException e) {
            // Degrade to a built-in template rather than failing the send outright: the message
            // content matters more than its styling. The fallback is substituted through the
            // same escaping path, so it is no less safe.
            log.error("Error loading template {}: {}", templateName, e.getMessage());
            template = getDefaultTemplate(templateName);
        }

        return replaceVariables(template, safeValues);
    }

    private String loadTemplate(String templateName) throws IOException {
        String templatePath = "templates/" + templateName;
        ClassPathResource resource = new ClassPathResource(templatePath);

        if (!resource.exists()) {
            throw new IOException("Template not found: " + templatePath);
        }

        return StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
    }

    /**
     * Substitutes placeholders in a single pass.
     *
     * <p>Single-pass matters for correctness as well as security: a value is written to the
     * output and never looked at again, so no value can influence how another is rendered.
     * Unknown placeholders resolve to an empty string rather than being left visible as
     * {@code {{something}}} in a delivered email.
     */
    private String replaceVariables(String template, Map<String, String> values) {
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder rendered = new StringBuilder();

        while (matcher.find()) {
            String name = matcher.group(1);
            String value = values.getOrDefault(name, "");
            matcher.appendReplacement(rendered, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(rendered);

        return rendered.toString();
    }

    private String getDefaultTemplate(String templateName) {
        return switch (templateName) {
            case "approval-email.html" -> getDefaultApprovalTemplate();
            case "account-approved.html" -> getDefaultApprovedTemplate();
            case "account-denied.html" -> getDefaultDeniedTemplate();
            case "registration-pending.html" -> getDefaultPendingTemplate();
            case "consultation-confirmation.html" -> getDefaultConsultationConfirmationTemplate();
            case "consultation-notification.html" -> getDefaultConsultationNotificationTemplate();
            case "password-reset.html" -> getDefaultPasswordResetTemplate();
            default -> "<html><body><h1>Email Template Error</h1><p>The requested template is "
                    + "unavailable.</p></body></html>";
        };
    }

    private String getDefaultApprovalTemplate() {
        return """
            <html><body>
            <h1>New User Registration Request</h1>
            <p>Name: {{userName}}</p>
            <p>Email: {{userEmail}}</p>
            <p><a href="{{approvalUrl}}">Approve</a> | <a href="{{denyUrl}}">Deny</a></p>
            </body></html>
            """;
    }

    private String getDefaultApprovedTemplate() {
        return """
            <html><body>
            <h1>Account Approved</h1>
            <p>Dear {{userName}}, your account has been approved!</p>
            <p><a href="{{loginUrl}}">Sign In Now</a></p>
            </body></html>
            """;
    }

    private String getDefaultDeniedTemplate() {
        return """
            <html><body>
            <h1>Account Registration Update</h1>
            <p>Dear {{userName}}, your account registration has been denied.</p>
            <p>Contact: {{adminEmail}}</p>
            </body></html>
            """;
    }

    private String getDefaultPendingTemplate() {
        return """
            <html><body>
            <h1>Registration Received</h1>
            <p>Dear {{userName}}, your registration is pending approval.</p>
            <p>Contact: {{adminEmail}}</p>
            </body></html>
            """;
    }

    private String getDefaultConsultationConfirmationTemplate() {
        return """
            <html><body>
            <h1>Consultation Confirmed</h1>
            <p>Dear {{firstName}} {{lastName}},</p>
            <p>Your consultation with {{company}} has been confirmed.</p>
            <p>Date: {{formattedDate}}</p>
            <p>Time: {{formattedTime}}</p>
            <p>Type: {{consultationType}}</p>
            <p><a href="{{meetingLink}}">Join Meeting</a></p>
            <p>Notes: {{notes}}</p>
            </body></html>
            """;
    }

    private String getDefaultConsultationNotificationTemplate() {
        return """
            <html><body>
            <h1>New Consultation Scheduled</h1>
            <p>Client: {{firstName}} {{lastName}}</p>
            <p>Email: {{email}}</p>
            <p>Company: {{company}}</p>
            <p>Phone: {{phone}}</p>
            <p>Date: {{formattedDate}}</p>
            <p>Time: {{formattedTime}}</p>
            <p>Type: {{consultationType}}</p>
            <p><a href="{{meetingLink}}">Join Meeting</a></p>
            <p>Notes: {{notes}}</p>
            </body></html>
            """;
    }

    private String getDefaultPasswordResetTemplate() {
        return """
            <html><body>
            <h1>Password Reset Request</h1>
            <p>Dear {{userName}},</p>
            <p>We received a request to reset your password.</p>
            <p><a href="{{resetUrl}}">Reset Password</a></p>
            <p>This link will expire in {{expiryTime}}.</p>
            <p>If you did not request this, please ignore this email.</p>
            <p>Contact: {{adminEmail}}</p>
            </body></html>
            """;
    }
}
