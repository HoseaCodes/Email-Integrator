package com.hoseacodes.emailintegrator.email;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for the domain invariants of a send command.
 *
 * <p>These duplicate some Bean Validation rules on purpose. Validation guards the HTTP boundary;
 * these guard the type itself, so a command constructed anywhere — a scheduled job, a future
 * internal caller, a test — cannot be malformed. A rule enforced only at the edge holds only for
 * traffic that arrives through that edge.
 */
class SendEmailCommandTest {

    private static final EmailAddress SENDER = new EmailAddress("sender@example.com", "Sender");
    private static final EmailAddress RECIPIENT = new EmailAddress("to@example.com", null);

    @Test
    @DisplayName("a well-formed command is accepted")
    void acceptsValidCommand() {
        SendEmailCommand command = new SendEmailCommand(
                SENDER, List.of(RECIPIENT), List.of(), List.of(), null,
                "Subject", "<p>Body</p>", "Body", List.of());

        assertThat(command.to()).containsExactly(RECIPIENT);
        assertThat(command.isMultiVariant()).isFalse();
    }

    @Test
    @DisplayName("requires a sender")
    void requiresSender() {
        assertThatThrownBy(() -> new SendEmailCommand(
                null, List.of(RECIPIENT), List.of(), List.of(), null,
                "Subject", "<p>Body</p>", null, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sender");
    }

    @Test
    @DisplayName("requires a subject")
    void requiresSubject() {
        assertThatThrownBy(() -> new SendEmailCommand(
                SENDER, List.of(RECIPIENT), List.of(), List.of(), null,
                "  ", "<p>Body</p>", null, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("subject");
    }

    @Test
    @DisplayName("requires content in at least one format")
    void requiresSomeContent() {
        assertThatThrownBy(() -> new SendEmailCommand(
                SENDER, List.of(RECIPIENT), List.of(), List.of(), null,
                "Subject", null, null, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("htmlContent or textContent");
    }

    @Test
    @DisplayName("requires at least one recipient, at the top level or in a variant")
    void requiresRecipients() {
        assertThatThrownBy(() -> new SendEmailCommand(
                SENDER, List.of(), List.of(), List.of(), null,
                "Subject", "<p>Body</p>", null, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("recipient");
    }

    @Test
    @DisplayName("recipients supplied only through variants satisfy the requirement")
    void variantsSatisfyRecipientRequirement() {
        SendEmailCommand command = new SendEmailCommand(
                SENDER, List.of(), List.of(), List.of(), null,
                "Subject", "<p>Body</p>", null,
                List.of(new MessageVariant(List.of(RECIPIENT), null, null)));

        assertThat(command.isMultiVariant()).isTrue();
        assertThat(command.totalRecipientCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("null collections are normalised to empty rather than causing NPEs later")
    void normalisesNullCollections() {
        SendEmailCommand command = new SendEmailCommand(
                SENDER, List.of(RECIPIENT), null, null, null,
                "Subject", "<p>Body</p>", null, null);

        assertThat(command.cc()).isEmpty();
        assertThat(command.bcc()).isEmpty();
        assertThat(command.variants()).isEmpty();
    }

    @Test
    @DisplayName("collections are defensively copied, so a caller cannot mutate a built command")
    void collectionsAreDefensivelyCopied() {
        List<EmailAddress> mutable = new ArrayList<>();
        mutable.add(RECIPIENT);

        SendEmailCommand command = new SendEmailCommand(
                SENDER, mutable, List.of(), List.of(), null,
                "Subject", "<p>Body</p>", null, List.of());

        mutable.add(new EmailAddress("sneaky@example.com", null));

        // Without the copy, adding a recipient after validation would smuggle it into the send.
        assertThat(command.to()).hasSize(1);
    }

    @Test
    @DisplayName("recipient counts include cc, bcc, and every variant")
    void countsAllRecipients() {
        SendEmailCommand command = new SendEmailCommand(
                SENDER,
                List.of(RECIPIENT, new EmailAddress("to2@example.com", null)),
                List.of(new EmailAddress("cc@example.com", null)),
                List.of(new EmailAddress("bcc@example.com", null)),
                null, "Subject", "<p>Body</p>", null,
                List.of(new MessageVariant(List.of(new EmailAddress("v@example.com", null)), null, null)));

        assertThat(command.totalRecipientCount()).isEqualTo(5);
    }

    @Test
    @DisplayName("an email address must not be blank")
    void emailAddressRequiresAValue() {
        assertThatThrownBy(() -> new EmailAddress("  ", "Name"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("email addresses are trimmed and blank display names normalised away")
    void emailAddressIsNormalised() {
        EmailAddress address = new EmailAddress("  person@example.com  ", "   ");

        assertThat(address.email()).isEqualTo("person@example.com");
        assertThat(address.name()).isNull();
    }

    @Test
    @DisplayName("a variant must name at least one recipient")
    void variantRequiresRecipients() {
        assertThatThrownBy(() -> new MessageVariant(List.of(), "Subject", "<p>Body</p>"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("recipient");
    }
}
