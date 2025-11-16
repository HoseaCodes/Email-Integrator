package com.hoseacodes.emailintegrator.model;

import java.util.List;

public class SimpleEmailRequest {
    private String from;
    private String fromName;
    private List<String> to;
    private List<String> cc;
    private List<String> bcc;
    private String subject;
    private String textContent;
    private String htmlContent;
    private String replyTo;
    
    public String getFrom() {
        return from;
    }
    
    public void setFrom(String from) {
        this.from = from;
    }
    
    public String getFromName() {
        return fromName;
    }
    
    public void setFromName(String fromName) {
        this.fromName = fromName;
    }
    
    public List<String> getTo() {
        return to;
    }
    
    public void setTo(List<String> to) {
        this.to = to;
    }
    
    public List<String> getCc() {
        return cc;
    }
    
    public void setCc(List<String> cc) {
        this.cc = cc;
    }
    
    public List<String> getBcc() {
        return bcc;
    }
    
    public void setBcc(List<String> bcc) {
        this.bcc = bcc;
    }
    
    public String getSubject() {
        return subject;
    }
    
    public void setSubject(String subject) {
        this.subject = subject;
    }
    
    public String getTextContent() {
        return textContent;
    }
    
    public void setTextContent(String textContent) {
        this.textContent = textContent;
    }
    
    public String getHtmlContent() {
        return htmlContent;
    }
    
    public void setHtmlContent(String htmlContent) {
        this.htmlContent = htmlContent;
    }
    
    public String getReplyTo() {
        return replyTo;
    }
    
    public void setReplyTo(String replyTo) {
        this.replyTo = replyTo;
    }
}
