package com.mywatchmate.notify;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * SMTP settings. With no host, emails are only logged (development).
 * For AWS SES: host email-smtp.&lt;region&gt;.amazonaws.com, port 587, SES SMTP username/password.
 */
@ConfigurationProperties("mywatchmate.mail")
public record MailSettings(String host, int port, String username, String password, String from) {

    public boolean configured() {
        return host != null && !host.isBlank();
    }
}
