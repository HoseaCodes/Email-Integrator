package com.hoseacodes.emailintegrator.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.hoseacodes.emailintegrator.brevo.delegate.BrevoEmailDelegate;
import com.hoseacodes.emailintegrator.model.EmailInput;
import com.hoseacodes.emailintegrator.model.EmailResponse;


@Component
public class EmailDeliveryService {
    
    @Autowired
    BrevoEmailDelegate brevoEmailDelegate;

    public EmailResponse deliverEmail(EmailInput input) throws Exception {
        EmailResponse response = null;
         try {
            response = input.getIsBatch() == true ? brevoEmailDelegate.callBatchCreateSmtpEmail(input) :  brevoEmailDelegate.callBrevoTranSmtpEmail(input);
        } catch (Exception e) {
            throw e; 
        }
        return response;
    }
}
