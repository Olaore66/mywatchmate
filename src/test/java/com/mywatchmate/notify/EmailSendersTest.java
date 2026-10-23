package com.mywatchmate.notify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

class EmailSendersTest {

    private final EmailMessage message = new EmailMessage("tunde@example.com", "Starting in 10 minutes: Room", "Hi Tunde");

    @Test
    void withoutAMailHostEmailsAreOnlyLogged() {
        EmailSender sender = new NotifyConfig().emailSender(new MailSettings("", 587, null, null, "no-reply@x.com"));

        assertThat(sender).isInstanceOf(LoggingEmailSender.class);
        assertThatCode(() -> sender.send(message)).doesNotThrowAnyException();
    }

    @Test
    void withAMailHostEmailsGoThroughSmtp() {
        EmailSender sender = new NotifyConfig().emailSender(
            new MailSettings("email-smtp.eu-west-2.amazonaws.com", 587, "user", "pass", "no-reply@x.com"));

        assertThat(sender).isInstanceOf(SmtpEmailSender.class);
    }

    @Test
    void smtpSenderFillsInFromToSubjectAndBody() {
        JavaMailSender mail = mock(JavaMailSender.class);

        new SmtpEmailSender(mail, "MyWatchMate <no-reply@mywatchmate.com>").send(message);

        ArgumentCaptor<SimpleMailMessage> sent = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mail).send(sent.capture());
        assertThat(sent.getValue().getFrom()).isEqualTo("MyWatchMate <no-reply@mywatchmate.com>");
        assertThat(sent.getValue().getTo()).containsExactly("tunde@example.com");
        assertThat(sent.getValue().getSubject()).isEqualTo("Starting in 10 minutes: Room");
        assertThat(sent.getValue().getText()).isEqualTo("Hi Tunde");
    }

    @Test
    void mailSettingsKnowWhenTheyreConfigured() {
        assertThat(new MailSettings(null, 587, null, null, null).configured()).isFalse();
        assertThat(new MailSettings("  ", 587, null, null, null).configured()).isFalse();
        assertThat(new MailSettings("smtp.example.com", 587, null, null, null).configured()).isTrue();
    }
}
