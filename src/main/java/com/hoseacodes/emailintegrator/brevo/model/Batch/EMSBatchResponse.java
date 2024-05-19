package com.hoseacodes.emailintegrator.brevo.model.Batch;

import java.util.List;

import com.hoseacodes.emailintegrator.model.EmailResponse;

public class EMSBatchResponse extends EmailResponse {
    private List<EMSBatchMessageIds> messageIds;

    public List<EMSBatchMessageIds> getMessageIds() {
        return messageIds;
    }

    public void setMessageIds(List<EMSBatchMessageIds> messageIds) {
        this.messageIds = messageIds;
    }
    
}
