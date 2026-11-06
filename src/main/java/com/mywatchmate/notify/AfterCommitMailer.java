package com.mywatchmate.notify;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Sends an email once the current transaction has committed, off the request's thread. A rollback sends
 * nothing, a slow mail server never slows the request, and response times can't reveal whether an email
 * was sent (which would tell a stranger an address has an account). A failure is logged; the person can
 * ask again. For one-time links, whose raw token can't be stored for an outbox.
 */
@Slf4j
@Component
public class AfterCommitMailer {

    private final EmailSender email;
    private final Executor executor;

    @Autowired
    public AfterCommitMailer(EmailSender email) {
        this(email, Executors.newVirtualThreadPerTaskExecutor());
    }

    AfterCommitMailer(EmailSender email, Executor executor) {
        this.email = email;
        this.executor = executor;
    }

    public void send(EmailMessage message) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    executor.execute(() -> deliver(message));
                }
            });
        } else {
            executor.execute(() -> deliver(message));
        }
    }

    private void deliver(EmailMessage message) {
        try {
            email.send(message);
        } catch (RuntimeException e) {
            log.warn("Email \"{}\" to {} failed: {}", message.subject(), message.to(), e.getMessage());
        }
    }
}
