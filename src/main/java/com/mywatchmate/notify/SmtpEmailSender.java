package com.mywatchmate.notify;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;

/** Sends through an SMTP server (AWS SES in production). */
@RequiredArgsConstructor
public class SmtpEmailSender implements EmailSender {

    private final JavaMailSender mail;
    private final String from;

    @Override
    public void send(EmailMessage message) {
        if (message.html() == null) {
            SimpleMailMessage email = new SimpleMailMessage();
            email.setFrom(from);
            email.setTo(message.to());
            email.setSubject(message.subject());
            email.setText(message.text());
            mail.send(email);
            return;
        }
        // Both versions in one email: mail apps show the HTML, or the text when they can't
        MimeMessage email = mail.createMimeMessage();
        try {
            MimeMessageHelper helper = new MimeMessageHelper(email, true, StandardCharsets.UTF_8.name());
            helper.setFrom(from);
            helper.setTo(message.to());
            helper.setSubject(message.subject());
            helper.setText(message.text(), message.html());
        } catch (MessagingException e) {
            throw new MailPreparationException("Couldn't build the email to " + message.to(), e);
        }
        mail.send(email);
    }
}
