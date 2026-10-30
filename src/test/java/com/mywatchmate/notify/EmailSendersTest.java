package com.mywatchmate.notify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
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
    void withAnHtmlVersionBothGoInOneEmail() throws Exception {
        JavaMailSender mail = mock(JavaMailSender.class);
        when(mail.createMimeMessage()).thenReturn(new MimeMessage((Session) null));

        new SmtpEmailSender(mail, "MyWatchMate <no-reply@mywatchmates.com>")
            .send(new EmailMessage("kemi@example.com", "Ada invited you", "Plain version", "<p>HTML version</p>"));

        ArgumentCaptor<MimeMessage> sent = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mail).send(sent.capture());
        MimeMessage email = sent.getValue();
        assertThat(email.getSubject()).isEqualTo("Ada invited you");
        assertThat(email.getFrom()[0].toString()).isEqualTo("MyWatchMate <no-reply@mywatchmates.com>");
        assertThat(email.getAllRecipients()[0].toString()).isEqualTo("kemi@example.com");
        email.saveChanges();
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        email.writeTo(raw);
        assertThat(raw.toString(StandardCharsets.UTF_8)).contains("Plain version").contains("<p>HTML version</p>")
            .contains("multipart/alternative");
    }

    @Test
    void mailSettingsKnowWhenTheyreConfigured() {
        assertThat(new MailSettings(null, 587, null, null, null).configured()).isFalse();
        assertThat(new MailSettings("  ", 587, null, null, null).configured()).isFalse();
        assertThat(new MailSettings("smtp.example.com", 587, null, null, null).configured()).isTrue();
    }
}
