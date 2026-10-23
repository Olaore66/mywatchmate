package com.mywatchmate.notify;

import lombok.RequiredArgsConstructor;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

/** Sends through an SMTP server (AWS SES in production). */
@RequiredArgsConstructor
public class SmtpEmailSender implements EmailSender {

    private final JavaMailSender mail;
    private final String from;

    @Override
    public void send(EmailMessage message) {
        SimpleMailMessage email = new SimpleMailMessage();
        email.setFrom(from);
        email.setTo(message.to());
        email.setSubject(message.subject());
        email.setText(message.text());
        mail.send(email);
    }
}
