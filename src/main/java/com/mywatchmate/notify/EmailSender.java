package com.mywatchmate.notify;

/** Sends one email; throws if it couldn't be handed to the mail server. */
public interface EmailSender {

    void send(EmailMessage message);
}
