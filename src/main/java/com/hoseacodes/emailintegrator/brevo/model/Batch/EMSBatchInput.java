package com.hoseacodes.emailintegrator.brevo.model.Batch;

import java.util.List;

import com.hoseacodes.emailintegrator.brevo.model.EMSParams;

public class EMSBatchInput {
    private EMSBatchSender sender;
    private String subject;
    private String htmlContent;
    private String cc;
    private String bcc;
    private List<EMSParams> params;
    private List<EMSMessageVersion> messageVersions;

    public EMSBatchSender getSender() {
        return sender;
    }

    public void setSender(EMSBatchSender sender) {
        this.sender = sender;
    }

    public String getSubject() {
        return subject;
    }

    public void setSubject(String subject) {
        this.subject = subject;
    }

    public String getHtmlContent() {
        return htmlContent;
    }

    public void setHtmlContent(String htmlContent) {
        this.htmlContent = htmlContent;
    }

    public List<EMSMessageVersion> getMessageVersions() {
        return messageVersions;
    }

    public void setMessageVersions(List<EMSMessageVersion> messageVersions) {
        this.messageVersions = messageVersions;
    }

    public String getCc() {
        return cc;
    }

    public void setCc(String cc) {
        this.cc = cc;
    }

    public String getBcc() {
        return bcc;
    }

    public void setBcc(String bcc) {
        this.bcc = bcc;
    }

    public List<EMSParams> getParams() {
        return params;
    }

    public void setParams(List<EMSParams> params) {
        this.params = params;
    }

}
