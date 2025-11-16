package com.hoseacodes.emailintegrator.controller;

import com.hoseacodes.emailintegrator.model.SimpleEmailRequest;
import com.hoseacodes.emailintegrator.model.SimpleEmailResponse;
import com.hoseacodes.emailintegrator.service.SpringMailService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/spring-mail")
public class SpringMailController {
    
    private static final Logger logger = LoggerFactory.getLogger(SpringMailController.class);
    
    @Autowired
    private SpringMailService springMailService;
    
    /**
     * Send a simple text email
     * POST /api/spring-mail/send-simple
     */
    @PostMapping("/send-simple")
    public ResponseEntity<SimpleEmailResponse> sendSimpleEmail(@RequestBody Map<String, String> request) {
        try {
            String to = request.get("to");
            String subject = request.get("subject");
            String text = request.get("text");
            
            if (to == null || subject == null || text == null) {
                SimpleEmailResponse errorResponse = new SimpleEmailResponse(false, 
                    "Missing required fields: to, subject, text");
                return ResponseEntity.badRequest().body(errorResponse);
            }
            
            SimpleEmailResponse response = springMailService.sendSimpleEmail(to, subject, text);
            
            if (response.isSuccess()) {
                return ResponseEntity.ok(response);
            } else {
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
            }
            
        } catch (Exception e) {
            logger.error("Error in sendSimpleEmail endpoint: {}", e.getMessage(), e);
            SimpleEmailResponse errorResponse = new SimpleEmailResponse(false, 
                "Internal server error: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }
    
    /**
     * Send an email with full options (HTML, CC, BCC, etc.)
     * POST /api/spring-mail/send
     */
    @PostMapping("/send")
    public ResponseEntity<SimpleEmailResponse> sendEmail(@RequestBody SimpleEmailRequest emailRequest) {
        try {
            SimpleEmailResponse response = springMailService.sendEmail(emailRequest);
            
            if (response.isSuccess()) {
                return ResponseEntity.ok(response);
            } else {
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
            }
            
        } catch (Exception e) {
            logger.error("Error in sendEmail endpoint: {}", e.getMessage(), e);
            SimpleEmailResponse errorResponse = new SimpleEmailResponse(false, 
                "Internal server error: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }
    
    /**
     * Health check endpoint
     * GET /api/spring-mail/health
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        return ResponseEntity.ok(Map.of(
            "status", "UP",
            "service", "SpringMailService",
            "timestamp", System.currentTimeMillis()
        ));
    }
}
