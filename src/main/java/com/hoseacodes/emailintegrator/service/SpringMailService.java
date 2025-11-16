package com.hoseacodes.emailintegrator.service;

import com.hoseacodes.emailintegrator.config.EmailProperties;
import com.hoseacodes.emailintegrator.model.SimpleEmailRequest;
import com.hoseacodes.emailintegrator.model.SimpleEmailResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import java.io.UnsupportedEncodingException;
import java.time.LocalDateTime;
import java.util.UUID;

@Service
public class SpringMailService {
    
    private static final Logger logger = LoggerFactory.getLogger(SpringMailService.class);
    
    @Autowired
    private JavaMailSender mailSender;
    
    @Autowired
    private EmailProperties emailProperties;
    
    /**
     * Send a simple text email
     */
    public SimpleEmailResponse sendSimpleEmail(String to, String subject, String text) {
        try {
            if (!emailProperties.isEnabled()) {
                logger.warn("Email service is disabled");
                return new SimpleEmailResponse(false, "Email service is disabled");
            }
            
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(getFromAddress());
            message.setTo(to);
            message.setSubject(subject);
            message.setText(text);
            
            mailSender.send(message);
            
            String messageId = UUID.randomUUID().toString();
            logger.info("Simple email sent successfully to {} with messageId: {}", to, messageId);
            
            SimpleEmailResponse response = new SimpleEmailResponse(true, "Email sent successfully");
            response.setMessageId(messageId);
            response.setSentAt(LocalDateTime.now());
            
            return response;
            
        } catch (MailException e) {
            logger.error("Failed to send simple email to {}: {}", to, e.getMessage(), e);
            return new SimpleEmailResponse(false, "Failed to send email: " + e.getMessage());
        }
    }
    
    /**
     * Send an email with full options (HTML, CC, BCC, etc.)
     */
    public SimpleEmailResponse sendEmail(SimpleEmailRequest emailRequest) {
        try {
            if (!emailProperties.isEnabled()) {
                logger.warn("Email service is disabled");
                return new SimpleEmailResponse(false, "Email service is disabled");
            }
            
            if (emailRequest.getTo() == null || emailRequest.getTo().isEmpty()) {
                return new SimpleEmailResponse(false, "No recipients specified");
            }
            
            MimeMessage mimeMessage = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, true, "UTF-8");
            
            // Set from address
            String fromAddress = StringUtils.hasText(emailRequest.getFrom()) 
                ? emailRequest.getFrom() 
                : getFromAddress();
            
            if (StringUtils.hasText(emailRequest.getFromName())) {
                helper.setFrom(fromAddress, emailRequest.getFromName());
            } else {
                helper.setFrom(fromAddress);
            }
            
            // Set recipients
            helper.setTo(emailRequest.getTo().toArray(new String[0]));
            
            if (emailRequest.getCc() != null && !emailRequest.getCc().isEmpty()) {
                helper.setCc(emailRequest.getCc().toArray(new String[0]));
            }
            
            if (emailRequest.getBcc() != null && !emailRequest.getBcc().isEmpty()) {
                helper.setBcc(emailRequest.getBcc().toArray(new String[0]));
            }
            
            // Set subject
            helper.setSubject(emailRequest.getSubject());
            
            // Set content (prefer HTML if available, fallback to text)
            if (StringUtils.hasText(emailRequest.getHtmlContent())) {
                helper.setText(
                    emailRequest.getTextContent() != null ? emailRequest.getTextContent() : "",
                    emailRequest.getHtmlContent()
                );
            } else if (StringUtils.hasText(emailRequest.getTextContent())) {
                helper.setText(emailRequest.getTextContent());
            } else {
                return new SimpleEmailResponse(false, "No email content provided");
            }
            
            // Set reply-to if provided
            if (StringUtils.hasText(emailRequest.getReplyTo())) {
                helper.setReplyTo(emailRequest.getReplyTo());
            }
            
            mailSender.send(mimeMessage);
            
            String messageId = UUID.randomUUID().toString();
            logger.info("Email sent successfully to {} recipients with messageId: {}", 
                emailRequest.getTo().size(), messageId);
            
            SimpleEmailResponse response = new SimpleEmailResponse(true, "Email sent successfully");
            response.setMessageId(messageId);
            response.setSentAt(LocalDateTime.now());
            
            return response;
            
        } catch (MailException | MessagingException | UnsupportedEncodingException e) {
            logger.error("Failed to send email: {}", e.getMessage(), e);
            return new SimpleEmailResponse(false, "Failed to send email: " + e.getMessage());
        }
    }
    
    private String getFromAddress() {
        return StringUtils.hasText(emailProperties.getDefaultFromAddress()) 
            ? emailProperties.getDefaultFromAddress() 
            : "noreply@example.com";
    }
}
