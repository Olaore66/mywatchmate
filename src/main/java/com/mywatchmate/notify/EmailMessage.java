package com.mywatchmate.notify;

/** An email: always plain text, optionally with an HTML version (shown by mail apps that can). */
public record EmailMessage(String to, String subject, String text, String html) {

    public EmailMessage(String to, String subject, String text) {
        this(to, subject, text, null);
    }
}
