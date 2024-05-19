package com.hoseacodes.emailintegrator.model;

import com.hoseacodes.emailintegrator.brevo.model.Batch.EMSBatchInput;

public class EmailInput {
    private String companySignature;
    private String templateType;
    private String requestId;
    private Boolean isBatch;
    private EMSBatchInput batchInput;

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

    public Boolean getIsBatch() {
        return isBatch;
    }

    public void setIsBatch(Boolean isBatch) {
        this.isBatch = isBatch;
    }

    public EMSBatchInput getBatchInput() {
        return batchInput;
    }

    public void setBatchInput(EMSBatchInput batchInput) {
        this.batchInput = batchInput;
    }
    
}
