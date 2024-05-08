package com.hoseacodes.emailintegrator;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class EmailController {

    @GetMapping("/email")
	public Email sendEmail(@RequestParam(value = "name", defaultValue = "World") String name) {
		return new Email("dominique@gmail.com");
	}
}
