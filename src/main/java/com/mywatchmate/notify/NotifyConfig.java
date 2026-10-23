package com.mywatchmate.notify;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSenderImpl;

@Slf4j
@Configuration
public class NotifyConfig {

    /** Real SMTP when MAIL_HOST is set; otherwise emails are printed to the log. */
    @Bean
    EmailSender emailSender(MailSettings settings) {
        if (!settings.configured()) {
            log.info("MAIL_HOST not set: reminder emails will be logged, not sent");
            return new LoggingEmailSender();
        }
        JavaMailSenderImpl mail = new JavaMailSenderImpl();
        mail.setHost(settings.host());
        mail.setPort(settings.port());
        mail.setUsername(settings.username());
        mail.setPassword(settings.password());
        mail.getJavaMailProperties().put("mail.smtp.auth", String.valueOf(settings.username() != null && !settings.username().isBlank()));
        mail.getJavaMailProperties().put("mail.smtp.starttls.enable", "true");
        mail.getJavaMailProperties().put("mail.smtp.connectiontimeout", "5000");
        mail.getJavaMailProperties().put("mail.smtp.timeout", "10000");
        return new SmtpEmailSender(mail, settings.from());
    }
}
