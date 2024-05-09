package com.hoseacodes.emailintegrator.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;

import com.hoseacodes.emailintegrator.model.EmailInput;
import com.hoseacodes.emailintegrator.model.EmailResponse;
import com.hoseacodes.emailintegrator.service.EmailDeliveryService;
import org.springframework.web.bind.annotation.PostMapping;


@RestController
public class EmailController {

	@Autowired
	private EmailDeliveryService emailService;

	@PostMapping("/email")
	@ResponseBody
	public EmailResponse sendEmail(@RequestBody EmailInput input) throws Exception {
		return emailService.deliverEmail(input);
	}
}
