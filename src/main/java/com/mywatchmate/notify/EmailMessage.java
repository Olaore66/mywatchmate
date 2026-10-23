package com.mywatchmate.notify;

/** A plain-text email. */
public record EmailMessage(String to, String subject, String text) {
}
