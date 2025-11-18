package com.hoseacodes.emailintegrator.service;

import com.hoseacodes.emailintegrator.config.EmailProperties;
import com.hoseacodes.emailintegrator.model.UserData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import java.io.UnsupportedEncodingException;
import java.util.Map;

@Service
public class UserApprovalEmailService {
    
    private static final Logger logger = LoggerFactory.getLogger(UserApprovalEmailService.class);
    
    @Autowired
    private JavaMailSender mailSender;
    
    @Autowired
    private EmailProperties emailProperties;
    
    @Autowired
    private ApprovalTokenService approvalTokenService;
    
    @Autowired
    private EmailTemplateService emailTemplateService;
    
    @Value("${app.base-url:http://localhost:8080}")
    private String baseUrl;
    
    @Value("${app.admin-email:admin@stormgate.com}")
    private String adminEmail;
    
    @Value("${app.name:Application}")
    private String appName;
    
    @Value("${app.display-name:User Management System}")
    private String appDisplayName;
    
    /**
     * Send approval request email to admin (equivalent to sendApprovalEmail)
     */
    public boolean sendApprovalEmail(UserData userData) {
        try {
            if (!emailProperties.isEnabled()) {
                logger.warn("Email service is disabled");
                return false;
            }
            
            String approvalToken = approvalTokenService.generateApprovalToken(userData.getEmail(), userData.getName());
            String approvalUrl = userData.getApprovalUrl();
            String denyUrl = userData.getDenyUrl();
            
            logger.info("Preparing approval email for {} to be sent to admin: {}", userData.getEmail(), adminEmail);
            
            MimeMessage mimeMessage = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, true, "UTF-8");
            
            helper.setFrom(getFromAddress(), "Storm Gate System");
            helper.setTo(adminEmail);
            helper.setSubject("New User Registration Approval Required - Storm Gate");
            
            String htmlContent = buildApprovalEmailTemplate(userData, approvalToken, approvalUrl, denyUrl);
            helper.setText("", htmlContent);
            
            mailSender.send(mimeMessage);
            logger.info("Approval email sent successfully for user: {}", userData.getEmail());
            
            return true;
            
        } catch (MailException | MessagingException | UnsupportedEncodingException e) {
            logger.error("Error sending approval email for user {}: {}", userData.getEmail(), e.getMessage(), e);
            return false;
        }
    }
    
    /**
     * Send account approved notification to user (equivalent to sendAccountApprovedEmail)
     */
    public boolean sendAccountApprovedEmail(UserData userData) {
        try {
            if (!emailProperties.isEnabled()) {
                logger.warn("Email service is disabled");
                return false;
            }
            
            MimeMessage mimeMessage = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, true, "UTF-8");
            
            helper.setFrom(getFromAddress(), "Storm Gate System");
            helper.setTo(userData.getEmail());
            helper.setSubject("Your Storm Gate Account Has Been Approved");
            
            String htmlContent = buildAccountApprovedTemplate(userData);
            helper.setText("", htmlContent);
            
            mailSender.send(mimeMessage);
            logger.info("Account approval notification sent to: {}", userData.getEmail());
            
            return true;
            
        } catch (MailException | MessagingException | UnsupportedEncodingException e) {
            logger.error("Error sending account approval notification to {}: {}", userData.getEmail(), e.getMessage(), e);
            return false;
        }
    }
    
    /**
     * Send account denied notification to user (equivalent to sendAccountDeniedEmail)
     */
    public boolean sendAccountDeniedEmail(UserData userData) {
        try {
            if (!emailProperties.isEnabled()) {
                logger.warn("Email service is disabled");
                return false;
            }
            
            MimeMessage mimeMessage = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, true, "UTF-8");
            
            helper.setFrom(getFromAddress(), "Storm Gate System");
            helper.setTo(userData.getEmail());
            helper.setSubject("Storm Gate Account Registration Status");
            
            String htmlContent = buildAccountDeniedTemplate(userData);
            helper.setText("", htmlContent);
            
            mailSender.send(mimeMessage);
            logger.info("Account denial notification sent to: {}", userData.getEmail());
            
            return true;
            
        } catch (MailException | MessagingException | UnsupportedEncodingException e) {
            logger.error("Error sending account denial notification to {}: {}", userData.getEmail(), e.getMessage(), e);
            return false;
        }
    }
    
    /**
     * Send registration pending email to user (equivalent to sendRegistrationPendingEmail)
     */
    public boolean sendRegistrationPendingEmail(UserData userData) {
        try {
            if (!emailProperties.isEnabled()) {
                logger.warn("Email service is disabled");
                return false;
            }
            
            MimeMessage mimeMessage = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, true, "UTF-8");
            
            helper.setFrom(getFromAddress(), "Storm Gate System");
            helper.setTo(userData.getEmail());
            helper.setSubject("Storm Gate Registration Received - Pending Approval");
            
            String htmlContent = buildRegistrationPendingTemplate(userData);
            helper.setText("", htmlContent);
            
            mailSender.send(mimeMessage);
            logger.info("Registration pending notification sent to: {}", userData.getEmail());
            
            return true;
            
        } catch (MailException | MessagingException | UnsupportedEncodingException e) {
            logger.error("Error sending registration pending notification to {}: {}", userData.getEmail(), e.getMessage(), e);
            return false;
        }
    }
    
    /**
     * Build HTML template for approval email to admin
     */
    private String buildApprovalEmailTemplate(UserData userData, String approvalToken, String approvalUrl, String denyUrl) {
        Map<String, String> variables = Map.of(
            "userName", userData.getName() != null ? userData.getName() : "User",
            "userEmail", userData.getEmail() != null ? userData.getEmail() : "",
            "approvalToken", approvalToken != null ? approvalToken : "",
            "approvalUrl", approvalUrl != null ? approvalUrl : "",
            "denyUrl", denyUrl != null ? denyUrl : "",
            "appName", getAppName(userData) != null ? getAppName(userData) : "Application",
            "appDisplayName", getAppDisplayName(userData) != null ? getAppDisplayName(userData) : "User Management System"
        );
        
        return emailTemplateService.processTemplate("approval-email.html", variables);
    }
    
    /**
     * Build HTML template for account approved email
     */
    private String buildAccountApprovedTemplate(UserData userData) {
        String loginUrl = getLoginUrl(userData);
        
        Map<String, String> variables = Map.of(
            "userName", userData.getName() != null ? userData.getName() : "User",
            "loginUrl", loginUrl != null ? loginUrl : "",
            "appName", getAppName(userData) != null ? getAppName(userData) : "Application",
            "appDisplayName", getAppDisplayName(userData) != null ? getAppDisplayName(userData) : "User Management System"
        );
        
        return emailTemplateService.processTemplate("account-approved.html", variables);
    }
    
    /**
     * Build HTML template for account denied email
     */
    private String buildAccountDeniedTemplate(UserData userData) {
        Map<String, String> variables = Map.of(
            "userName", userData.getName() != null ? userData.getName() : "User",
            "adminEmail", adminEmail != null ? adminEmail : "",
            "appName", getAppName(userData) != null ? getAppName(userData) : "Application",
            "appDisplayName", getAppDisplayName(userData) != null ? getAppDisplayName(userData) : "User Management System"
        );
        
        return emailTemplateService.processTemplate("account-denied.html", variables);
    }
    
    /**
     * Build HTML template for registration pending email
     */
    private String buildRegistrationPendingTemplate(UserData userData) {
        Map<String, String> variables = Map.of(
            "userName", userData.getName() != null ? userData.getName() : "User",
            "adminEmail", adminEmail != null ? adminEmail : "",
            "appName", getAppName(userData) != null ? getAppName(userData) : "Application",
            "appDisplayName", getAppDisplayName(userData) != null ? getAppDisplayName(userData) : "User Management System"
        );
        
        return emailTemplateService.processTemplate("registration-pending.html", variables);
    }
    
    private String getFromAddress() {
        return StringUtils.hasText(emailProperties.getDefaultFromAddress()) 
            ? emailProperties.getDefaultFromAddress() 
            : "noreply@stormgate.com";
    }
    
    /**
     * Get app name from UserData or fallback to configuration
     */
    private String getAppName(UserData userData) {
        return StringUtils.hasText(userData.getAppName()) 
            ? userData.getAppName() 
            : appName;
    }
    
    /**
     * Get app display name from UserData or fallback to configuration
     */
    private String getAppDisplayName(UserData userData) {
        return StringUtils.hasText(userData.getAppDisplayName()) 
            ? userData.getAppDisplayName() 
            : appDisplayName;
    }
    
    /**
     * Get login URL from UserData or fallback to configuration
     */
    private String getLoginUrl(UserData userData) {
        return StringUtils.hasText(userData.getLoginUrl()) 
            ? userData.getLoginUrl() 
            : baseUrl + "/login";
    }
}
