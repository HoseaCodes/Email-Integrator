package com.hoseacodes.emailintegrator.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.time.Duration;
import java.util.Properties;

@Configuration
public class MailConfig {

    @Value("${spring.mail.host:smtp.gmail.com}")
    private String host;

    @Value("${spring.mail.port:587}")
    private int port;

    @Value("${spring.mail.username:}")
    private String username;

    @Value("${spring.mail.password:}")
    private String password;

    /** Cap on establishing the TCP connection to the SMTP server. */
    @Value("${app.mail.connection-timeout:5s}")
    private Duration connectionTimeout;

    /** Cap on waiting for a response to any single SMTP command. */
    @Value("${app.mail.read-timeout:10s}")
    private Duration readTimeout;

    /**
     * Cap on writing message data. Held separately and set higher because a large HTML body or an
     * attachment legitimately takes longer to transmit than a command takes to answer.
     */
    @Value("${app.mail.write-timeout:15s}")
    private Duration writeTimeout;

    @Bean
    public JavaMailSender getJavaMailSender() {
        JavaMailSenderImpl mailSender = new JavaMailSenderImpl();
        
        mailSender.setHost(host);
        mailSender.setPort(port);
        mailSender.setUsername(username);
        mailSender.setPassword(password);

        Properties props = mailSender.getJavaMailProperties();
        props.put("mail.transport.protocol", "smtp");
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.starttls.enable", "true");
        props.put("mail.smtp.starttls.required", "true");
        props.put("mail.smtp.ssl.trust", host);

        // Timeouts. JavaMail defaults every one of these to infinite, so without them a send
        // against an unresponsive SMTP server holds its Tomcat worker forever. Under sustained
        // slowness the pool drains and the service stops answering every endpoint, including
        // /actuator/health — at which point the platform replaces the instance mid-flight.
        //
        // This is the same reasoning applied to the Brevo client in BrevoClientConfig, and it
        // matters more here: an SMTP conversation is several round trips, not one request.
        //
        // Values in milliseconds, as JavaMail expects.
        props.put("mail.smtp.connectiontimeout", String.valueOf(connectionTimeout.toMillis()));
        props.put("mail.smtp.timeout", String.valueOf(readTimeout.toMillis()));
        props.put("mail.smtp.writetimeout", String.valueOf(writeTimeout.toMillis()));

        // Debug logging prints the entire SMTP conversation — including the AUTH command and
        // therefore the credentials. Never enable it outside a local investigation.
        props.put("mail.debug", "false");

        return mailSender;
    }
}
