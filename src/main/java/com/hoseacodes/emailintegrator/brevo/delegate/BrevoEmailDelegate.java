package com.hoseacodes.emailintegrator.brevo.delegate;

import org.springframework.stereotype.Component;

import sendinblue.*;
import sendinblue.auth.*;
import sibModel.*;
import sibApi.TransactionalEmailsApi;

import java.io.File;
import java.util.*;

@Component
public class BrevoEmailDelegate {
    
    public CreateSmtpEmail callBrevoTranSmtpEmail(SendSmtpEmail input) throws ApiException {
        ApiClient defaultClient = Configuration.getDefaultApiClient();
        ApiKeyAuth apiKey = (ApiKeyAuth) defaultClient.getAuthentication("api-key");
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


