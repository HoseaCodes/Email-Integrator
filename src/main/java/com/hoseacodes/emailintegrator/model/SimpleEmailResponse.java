package com.hoseacodes.emailintegrator.model;

import java.time.LocalDateTime;

public class SimpleEmailResponse {
    private boolean success;
    private String messageId;
    private String message;
    private LocalDateTime sentAt;
    private String provider = "spring-mail";
    
    public SimpleEmailResponse() {
    }
    
    public SimpleEmailResponse(boolean success, String message) {
        this.success = success;
        this.message = message;
        this.sentAt = LocalDateTime.now();
    }
    
    public boolean isSuccess() {
        return success;
    }
    
    public void setSuccess(boolean success) {
        this.success = success;
    }
    
    public String getMessageId() {
        return messageId;
    }
    
    public void setMessageId(String messageId) {
        this.messageId = messageId;
    }
    
    public String getMessage() {
        return message;
    }
    
    public void setMessage(String message) {
        this.message = message;
    }
    
    public LocalDateTime getSentAt() {
        return sentAt;
    }
    
    public void setSentAt(LocalDateTime sentAt) {
        this.sentAt = sentAt;
    }
    
    public String getProvider() {
        return provider;
    }
    
    public void setProvider(String provider) {
        this.provider = provider;
    }
}
