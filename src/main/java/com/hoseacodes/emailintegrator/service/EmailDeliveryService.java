package com.hoseacodes.emailintegrator.service;

import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.hoseacodes.emailintegrator.brevo.delegate.BrevoEmailDelegate;
import com.hoseacodes.emailintegrator.model.EmailInput;
import com.hoseacodes.emailintegrator.model.EmailResponse;
import sibModel.SendSmtpEmail;
import sibModel.SendSmtpEmailReplyTo;
import sibModel.SendSmtpEmailSender;
import sibModel.SendSmtpEmailTo;

@Component
public class EmailDeliveryService {
    
    @Autowired
    BrevoEmailDelegate brevoEmailDelegate;

    public EmailResponse deliverEmail(EmailInput input) throws Exception {
         System.out.println("result");
         String emailAddress = input.getCompanySignature() + "@gmail.com";
         EmailResponse response = null;
         System.out.println("result2");
        SendSmtpEmail emailInput = new SendSmtpEmail();
        emailInput.setSubject("Welcome to Brevo");
        emailInput.setHtmlContent("Welcome to Brevo, " + input.getCompanySignature() + "!");
        SendSmtpEmailSender sender = new SendSmtpEmailSender();
        sender.setEmail("info@ambitiousconcpets.com");
        sender.setName("Ambitious Concepts");
        emailInput.setSender(sender);
        SendSmtpEmailReplyTo replyTo = new SendSmtpEmailReplyTo();
        replyTo.setEmail("ann6533@example.com");
        replyTo.setName("Ann");
        emailInput.setReplyTo(replyTo);
        emailInput.setTextContent("Welcome to Brevo, " + input.getCompanySignature() + "!");
        SendSmtpEmailTo to = new SendSmtpEmailTo();
        to.setEmail("mr.dhosea@gmail.com");
        List<SendSmtpEmailTo> toList = new ArrayList<>();
        toList.add(to);
        emailInput.setTo(toList);
         System.out.println(emailInput);
        try {
            brevoEmailDelegate.callBrevoTranSmtpEmail(emailInput);
            response = new EmailResponse();
            response.setGetId(emailAddress);
            response.setType("email");
        } catch (Exception e) {
            throw e; 
        }
        return response;
    }
}
