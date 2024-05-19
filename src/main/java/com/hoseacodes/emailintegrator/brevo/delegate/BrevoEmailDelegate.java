package com.hoseacodes.emailintegrator.brevo.delegate;

import com.hoseacodes.emailintegrator.brevo.model.Batch.EMSBatchInput;
import com.hoseacodes.emailintegrator.brevo.model.Batch.EMSBatchResponse;
import com.hoseacodes.emailintegrator.config.BrevoConfiguration;
import com.hoseacodes.emailintegrator.model.EmailInput;
import com.hoseacodes.emailintegrator.model.EmailResponse;

import java.io.IOError;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import sendinblue.*;
import sendinblue.auth.*;
import sibModel.*;
import sibApi.TransactionalEmailsApi;

@Component
public class BrevoEmailDelegate {

    @Autowired
    private BrevoConfiguration brevoConfiguration;

    @Autowired
    RestTemplate restTemplate;

    public EmailResponse callBrevoTranSmtpEmail(EmailInput input) throws ApiException {
        setBrevoAPIKey();
        SendSmtpEmail emailInput = convertSmtpInput(input);
        TransactionalEmailsApi apiInstance = new TransactionalEmailsApi();
        try {
            CreateSmtpEmail result = apiInstance.sendTransacEmail(emailInput);
            EmailResponse res = new EmailResponse();
            res.setId(result.getMessageId());
            res.setType("email");
            System.out.println(result);
            return res;
        } catch (ApiException e) {
            System.err.println("Exception when calling TransactionalEmailsApi#sendTransacEmail");
            e.printStackTrace();
            throw e;
        }
    }

    public EMSBatchResponse callBatchCreateSmtpEmail(EmailInput input) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setAccept(Arrays.asList(MediaType.APPLICATION_JSON));
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("api-key", brevoConfiguration.getApikey());
            EMSBatchInput batchInput = input.getBatchInput();
            HttpEntity<EMSBatchInput> entity = new HttpEntity<>(batchInput, headers);
            EMSBatchResponse response = restTemplate.exchange("https://api.brevo.com/v3/smtp/email", HttpMethod.POST, entity,
                    EMSBatchResponse.class).getBody();
            return response;
        } catch (HttpStatusCodeException e) {
            try {
                String error = e.getResponseBodyAsString();
                // System.out.println("Error calling Brevo API: " + error, e);
                System.out.println("Error calling Brevo API: " + error);
                throw e;
            } catch (IOError err) {
                throw err;
            }
        } 
    }

    private ApiKeyAuth setBrevoAPIKey() {
        ApiClient defaultClient = Configuration.getDefaultApiClient();
        ApiKeyAuth apiKey = (ApiKeyAuth) defaultClient.getAuthentication("api-key");
        String brevoApiKey = brevoConfiguration.getApikey();
        apiKey.setApiKey(brevoApiKey);
        return apiKey;
    }

    private SendSmtpEmail convertSmtpInput(EmailInput input) {
        System.out.println("result");
        String emailAddress = input.getCompanySignature() + "@gmail.com";
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
        return emailInput;
    }
}
