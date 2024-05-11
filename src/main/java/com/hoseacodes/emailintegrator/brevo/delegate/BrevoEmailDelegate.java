package com.hoseacodes.emailintegrator.brevo.delegate;

import com.hoseacodes.emailintegrator.config.BrevoConfiguration;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import sendinblue.*;
import sendinblue.auth.*;
import sibModel.*;
import sibApi.TransactionalEmailsApi;

@Component
public class BrevoEmailDelegate {
    
    @Autowired
    private BrevoConfiguration brevoConfiguration;
   
    public CreateSmtpEmail callBrevoTranSmtpEmail(SendSmtpEmail input) throws ApiException {
        ApiClient defaultClient = Configuration.getDefaultApiClient();
        ApiKeyAuth apiKey = (ApiKeyAuth) defaultClient.getAuthentication("api-key");
        String brevoApiKey = brevoConfiguration.getApikey();
        apiKey.setApiKey(brevoApiKey);
        System.out.println(defaultClient);
        System.out.println(apiKey);


        TransactionalEmailsApi apiInstance = new TransactionalEmailsApi();
        // SendSmtpEmail sendSmtpEmail = new SendSmtpEmail(); // SendSmtpEmail | Values to send a transactional email
        try {
            CreateSmtpEmail result = apiInstance.sendTransacEmail(input);
            System.out.println(result);
            return result;
        } catch (ApiException e) {
            System.err.println("Exception when calling TransactionalEmailsApi#sendTransacEmail");
            e.printStackTrace();
            throw e;
        }
    }
}


