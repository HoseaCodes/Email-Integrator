package com.hoseacodes.emailintegrator.service;

/**
 * Thrown when {@code app.email.enabled=false} and a send is attempted.
 *
 * <p>The kill switch exists so a non-production environment can run the full request path —
 * validation, mapping, error handling — without delivering real mail to real people. Making it
 * an explicit failure rather than a silent no-op matters: a test that "passes" because nothing
 * was sent teaches you nothing, and a caller told "202 accepted" for a message that will never
 * arrive has been misinformed.
 */
public class EmailSendingDisabledException extends RuntimeException {

    public EmailSendingDisabledException() {
        super("Email sending is disabled by configuration (app.email.enabled=false)");
    }
}
