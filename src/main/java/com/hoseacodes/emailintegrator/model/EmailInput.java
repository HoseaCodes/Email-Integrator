package com.hoseacodes.emailintegrator.model;

public class EmailInput {
    private String companySignature;
    private String templateType;
    private String requestId;

    public String getCompanySignature() {
        return companySignature;
    }

    public void setCompanySignature(String companySignature) {
        this.companySignature = companySignature;
    }

    public String getTemplateType() {
        return templateType;
    }

    public void setTemplateType(String templateType) {
        this.templateType = templateType;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }
    
}
