package com.hoseacodes.emailintegrator.brevo.model.Batch;

import java.util.List;

public class EMSMessageVersion {
    private String htmlContent;
    private String subject;
    private List<EmailBatchTo> to;

    public String getHtmlContent() {
        return htmlContent;
    }

    public void setHtmlContent(String htmlContent) {
        this.htmlContent = htmlContent;
    }

    public String getSubject() {
        return subject;
    }

    public void setSubject(String subject) {
        this.subject = subject;
    }

    public List<EmailBatchTo> getTo() {
        return to;
    }

    public void setTo(List<EmailBatchTo> to) {
        this.to = to;
    }
}
