package com.hoseacodes.emailintegrator.service;

import org.springframework.stereotype.Component;

import com.hoseacodes.emailintegrator.model.EmailInput;
import com.hoseacodes.emailintegrator.model.EmailResponse;

@Component
public class EmailDeliveryService {
    

    public EmailResponse deliverEmail(EmailInput input) {
        String emailAddress = input.getCompanySignature() + "@gmail.com";
        EmailResponse response = null;
        try {
            response = new EmailResponse();
            response.setGetId(emailAddress);
            response.setType("email");
        } catch (Exception e) {
            throw e; 
        }
        return response;
    }
}
