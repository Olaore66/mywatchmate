package com.mywatchmate.notify;

import lombok.extern.slf4j.Slf4j;

/** Development: prints the email instead of sending it. Used when no mail server is configured. */
@Slf4j
public class LoggingEmailSender implements EmailSender {

    @Override
    public void send(EmailMessage message) {
        log.info("""

            ---- email (not sent: MAIL_HOST is not set) ----
            To: {}
            Subject: {}

            {}
            ------------------------------------------------""", message.to(), message.subject(), message.text());
    }
}
