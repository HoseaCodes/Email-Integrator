package com.hoseacodes.emailintegrator.controller;

import com.hoseacodes.emailintegrator.model.UserData;
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
    public ResponseEntity<Map<String, Object>> sendEmail(@RequestBody Map<String, String> request) {
        try {
            String email = request.get("email");
            String name = request.get("name");
            String templateType = request.get("templateType");
            String appName = request.get("appName");
            String appDisplayName = request.get("appDisplayName");
            String approvalUrl = request.get("approvalUrl");
            String denyUrl = request.get("denyUrl");
            String loginUrl = request.get("loginUrl");
            
            if (email == null || name == null || templateType == null) {
                return ResponseEntity.badRequest().body(Map.of("error", "Email, name, and templateType are required"));
            }
            
            logger.info("Sending {} email to: {}", templateType, email);
            UserData userData = new UserData(email, name, appName, appDisplayName, approvalUrl, denyUrl, loginUrl);
            
            boolean emailSent = false;
            String message = "";
            
            switch (templateType.toLowerCase()) {
                case "approval":
                    emailSent = userApprovalEmailService.sendApprovalEmail(userData);
                    message = "Approval request email sent to admin";
                    break;
                case "approved":
                    emailSent = userApprovalEmailService.sendAccountApprovedEmail(userData);
                    message = "Account approved email sent to user";
                    break;
                case "denied":
                    emailSent = userApprovalEmailService.sendAccountDeniedEmail(userData);
                    message = "Account denied email sent to user";
                    break;
                case "pending":
                    emailSent = userApprovalEmailService.sendRegistrationPendingEmail(userData);
                    message = "Registration pending email sent to user";
                    break;
                default:
                    return ResponseEntity.badRequest().body(Map.of(
                        "error", "Invalid templateType",
                        "message", "Valid types: approval, approved, denied, pending"
                    ));
            }
            
            if (emailSent) {
                return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", message,
                    "templateType", templateType,
                    "recipient", email
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
    
}
