package com.hoseacodes.emailintegrator.controller;

import com.hoseacodes.emailintegrator.model.UserData;
import com.hoseacodes.emailintegrator.model.ConsultationData;
import com.hoseacodes.emailintegrator.service.ApprovalTokenService;
import com.hoseacodes.emailintegrator.service.UserApprovalEmailService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/auth")
public class UserApprovalController {
    
    private static final Logger logger = LoggerFactory.getLogger(UserApprovalController.class);
    
    @Autowired
    private ApprovalTokenService approvalTokenService;
    
    @Autowired
    private UserApprovalEmailService userApprovalEmailService;
    
    /**
     * Approve user account
     * GET /auth/approve?token=...
     */
    @GetMapping("/approve")
    public ResponseEntity<Map<String, Object>> approveUser(@RequestParam String token) {
        try {
            if (token == null || token.trim().isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Token is required"));
            }
            
            Map<String, Object> userData = approvalTokenService.verifyApprovalTokenWithClaims(token);
            if (userData == null) {
                return ResponseEntity.badRequest().body(Map.of("error", "Invalid or expired token"));
            }
            
            String userEmail = (String) userData.get("email");
            String userName = (String) userData.get("name");
            
            if (userEmail == null) {
                return ResponseEntity.badRequest().body(Map.of("error", "Invalid token data"));
            }
            
            // Here you would typically update the user status in your database
            // For now, we'll simulate this step
            logger.info("User approved: {}", userEmail);
            
            // Send approval notification to user
            UserData user = new UserData(userEmail, userName != null ? userName : "User");
            boolean emailSent = userApprovalEmailService.sendAccountApprovedEmail(user);
            
            return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "User account has been approved successfully",
                "emailSent", emailSent,
                "user", Map.of(
                    "email", userEmail,
                    "name", userName != null ? userName : "User",
                    "status", "APPROVED"
                )
            ));
            
        } catch (Exception e) {
            logger.error("Error in approval route:", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Internal server error"));
        }
    }
    
    /**
     * Deny user account
     * GET /auth/deny?token=...
     */
    @GetMapping("/deny")
    public ResponseEntity<Map<String, Object>> denyUser(@RequestParam String token) {
        try {
            if (token == null || token.trim().isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Token is required"));
            }
            
            Map<String, Object> userData = approvalTokenService.verifyApprovalTokenWithClaims(token);
            if (userData == null) {
                return ResponseEntity.badRequest().body(Map.of("error", "Invalid or expired token"));
            }
            
            String userEmail = (String) userData.get("email");
            String userName = (String) userData.get("name");
            
            if (userEmail == null) {
                return ResponseEntity.badRequest().body(Map.of("error", "Invalid token data"));
            }
            
            // Here you would typically update the user status in your database
            logger.info("User denied: {}", userEmail);
            
            // Send denial notification to user
            UserData user = new UserData(userEmail, userName != null ? userName : "User");
            boolean emailSent = userApprovalEmailService.sendAccountDeniedEmail(user);
            
            return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "User account has been denied",
                "emailSent", emailSent,
                "user", Map.of(
                    "email", userEmail,
                    "name", userName != null ? userName : "User",
                    "status", "DENIED"
                )
            ));
            
        } catch (Exception e) {
            logger.error("Error in deny route:", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Internal server error"));
        }
    }
    
    /**
     * Manually approve user by admin
     * POST /auth/manual-approve
     */
    @PostMapping("/manual-approve")
    public ResponseEntity<Map<String, Object>> manuallyApproveUser(@RequestBody Map<String, String> request) {
        try {
            String userEmail = request.get("email");
            String userName = request.get("name");
            
            if (userEmail == null || userEmail.trim().isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Email is required"));
            }
            
            // Here you would typically update the user status in your database
            logger.info("User manually approved: {}", userEmail);
            
            // Send approval notification to user
            UserData user = new UserData(userEmail, userName != null ? userName : "User");
            boolean emailSent = userApprovalEmailService.sendAccountApprovedEmail(user);
            
            return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "User approved successfully",
                "emailSent", emailSent,
                "user", Map.of(
                    "email", userEmail,
                    "name", userName != null ? userName : "User",
                    "status", "APPROVED"
                )
            ));
            
        } catch (Exception e) {
            logger.error("Error in manual approval:", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Internal server error"));
        }
    }
    
    /**
     * Manually deny user by admin
     * POST /auth/manual-deny
     */
    @PostMapping("/manual-deny")
    public ResponseEntity<Map<String, Object>> manuallyDenyUser(@RequestBody Map<String, String> request) {
        try {
            String userEmail = request.get("email");
            String userName = request.get("name");
            
            if (userEmail == null || userEmail.trim().isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Email is required"));
            }
            
            // Here you would typically update the user status in your database
            logger.info("User manually denied: {}", userEmail);
            
            // Send denial notification to user
            UserData user = new UserData(userEmail, userName != null ? userName : "User");
            boolean emailSent = userApprovalEmailService.sendAccountDeniedEmail(user);
            
            return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "User denied successfully",
                "emailSent", emailSent,
                "user", Map.of(
                    "email", userEmail,
                    "name", userName != null ? userName : "User",
                    "status", "DENIED"
                )
            ));
            
        } catch (Exception e) {
            logger.error("Error in manual denial:", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Internal server error"));
        }
    }
    
    /**
     * Send email based on template type
     * POST /auth/send-email
     */
    @PostMapping("/send-email")
    public ResponseEntity<Map<String, Object>> sendEmail(@RequestBody Map<String, Object> request) {
        try {
            String templateType = (String) request.get("templateType");
            
            if (templateType == null) {
                return ResponseEntity.badRequest().body(Map.of("error", "templateType is required"));
            }
            
            logger.info("Sending {} email", templateType);
            
            EmailResult result = switch (templateType.toLowerCase()) {
                case "approval", "approved", "denied", "pending" -> 
                    handleUserApprovalEmail(templateType, request);
                case "consultation-confirmation", "consultation-notification" -> 
                    handleConsultationEmail(templateType, request);
                default -> new EmailResult(false, "Invalid templateType", "", 
                    "Valid types: approval, approved, denied, pending, consultation-confirmation, consultation-notification");
            };
            
            if (result.hasError()) {
                return ResponseEntity.badRequest().body(Map.of("error", result.errorMessage()));
            }
            
            if (result.success()) {
                return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", result.message(),
                    "templateType", templateType,
                    "recipient", result.recipient()
                ));
            } else {
                return ResponseEntity.badRequest().body(Map.of(
                    "error", "Failed to send email",
                    "message", "Check server logs for detailed error information",
                    "templateType", templateType
                ));
            }
            
        } catch (Exception e) {
            logger.error("Error sending email:", e);
            String errorMessage = e.getMessage() != null ? e.getMessage() : "Unknown error occurred";
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Internal server error", "details", errorMessage));
        }
    }
    
    /**
     * Handle user approval emails (approval, approved, denied, pending)
     */
    private EmailResult handleUserApprovalEmail(String templateType, Map<String, Object> request) {
        String email = (String) request.get("email");
        String name = (String) request.get("name");
        
        if (email == null || name == null) {
            return EmailResult.error("Email and name are required");
        }
        
        String appName = (String) request.get("appName");
        String appDisplayName = (String) request.get("appDisplayName");
        String approvalUrl = (String) request.get("approvalUrl");
        String denyUrl = (String) request.get("denyUrl");
        String loginUrl = (String) request.get("loginUrl");
        
        UserData userData = new UserData(email, name, appName, appDisplayName, approvalUrl, denyUrl, loginUrl);
        
        return switch (templateType.toLowerCase()) {
            case "approval" -> {
                boolean sent = userApprovalEmailService.sendApprovalEmail(userData);
                yield new EmailResult(sent, "Approval request email sent to admin", email, null);
            }
            case "approved" -> {
                boolean sent = userApprovalEmailService.sendAccountApprovedEmail(userData);
                yield new EmailResult(sent, "Account approved email sent to user", email, null);
            }
            case "denied" -> {
                boolean sent = userApprovalEmailService.sendAccountDeniedEmail(userData);
                yield new EmailResult(sent, "Account denied email sent to user", email, null);
            }
            case "pending" -> {
                boolean sent = userApprovalEmailService.sendRegistrationPendingEmail(userData);
                yield new EmailResult(sent, "Registration pending email sent to user", email, null);
            }
            default -> EmailResult.error("Invalid approval template type");
        };
    }
    
    /**
     * Handle consultation emails (consultation-confirmation, consultation-notification)
     */
    private EmailResult handleConsultationEmail(String templateType, Map<String, Object> request) {
        String firstName = (String) request.get("firstName");
        String lastName = (String) request.get("lastName");
        String email = (String) request.get("email");
        String company = (String) request.get("company");
        String consultationType = (String) request.get("consultationType");
        String date = (String) request.get("date");
        String timeSlot = (String) request.get("timeSlot");
        String meetingLink = (String) request.get("meetingLink");
        
        if (firstName == null || lastName == null || email == null || 
            company == null || consultationType == null || date == null || 
            timeSlot == null || meetingLink == null) {
            return EmailResult.error("firstName, lastName, email, company, consultationType, date, timeSlot, and meetingLink are required for consultation emails");
        }
        
        String phone = (String) request.get("phone");
        String notes = (String) request.get("notes");
        
        ConsultationData consultationData = new ConsultationData(
            firstName, lastName, email, company, consultationType, 
            date, timeSlot, meetingLink, phone, notes
        );
        
        return switch (templateType.toLowerCase()) {
            case "consultation-confirmation" -> {
                boolean sent = userApprovalEmailService.sendConsultationConfirmationEmail(consultationData);
                yield new EmailResult(sent, "Consultation confirmation email sent to user", email, null);
            }
            case "consultation-notification" -> {
                boolean sent = userApprovalEmailService.sendConsultationNotificationEmail(consultationData);
                yield new EmailResult(sent, "Consultation notification email sent to admin", "info@ambitiousconcept.com", null);
            }
            default -> EmailResult.error("Invalid consultation template type");
        };
    }
    
    /**
     * Result record for email operations
     */
    private record EmailResult(boolean success, String message, String recipient, String errorMessage) {
        static EmailResult error(String errorMessage) {
            return new EmailResult(false, "", "", errorMessage);
        }
        
        boolean hasError() {
            return errorMessage != null && !errorMessage.isEmpty();
        }
    }
    
}
