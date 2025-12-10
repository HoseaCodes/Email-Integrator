package com.hoseacodes.emailintegrator.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

@Service
public class EmailTemplateService {
    
    private static final Logger logger = LoggerFactory.getLogger(EmailTemplateService.class);
    
    /**
     * Load and process email template with variables
     */
    public String processTemplate(String templateName, Map<String, String> variables) {
        try {
            String template = loadTemplate(templateName);
            return replaceVariables(template, variables);
        } catch (IOException e) {
            logger.error("Error processing template {}: {}", templateName, e.getMessage());
            return getDefaultTemplate(templateName);
        }
    }
    
    /**
     * Load template from resources/templates directory
     */
    private String loadTemplate(String templateName) throws IOException {
        String templatePath = "templates/" + templateName;
        ClassPathResource resource = new ClassPathResource(templatePath);
        
        if (!resource.exists()) {
            throw new IOException("Template not found: " + templatePath);
        }
        
        return StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
    }
    
    /**
     * Replace template variables with actual values
     */
    private String replaceVariables(String template, Map<String, String> variables) {
        String result = template;
        
        for (Map.Entry<String, String> entry : variables.entrySet()) {
            String placeholder = "{{" + entry.getKey() + "}}";
            String value = entry.getValue() != null ? entry.getValue() : "";
            result = result.replace(placeholder, value);
        }
        
        return result;
    }
    
    /**
     * Get default template if file loading fails
     */
    private String getDefaultTemplate(String templateName) {
        return switch (templateName) {
            case "approval-email.html" -> getDefaultApprovalTemplate();
            case "account-approved.html" -> getDefaultApprovedTemplate();
            case "account-denied.html" -> getDefaultDeniedTemplate();
            case "registration-pending.html" -> getDefaultPendingTemplate();
            case "consultation-confirmation.html" -> getDefaultConsultationConfirmationTemplate();
            case "consultation-notification.html" -> getDefaultConsultationNotificationTemplate();
            case "password-reset.html" -> getDefaultPasswordResetTemplate();
            default -> "<html><body><h1>Email Template Error</h1><p>Template not found: " + templateName + "</p></body></html>";
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
