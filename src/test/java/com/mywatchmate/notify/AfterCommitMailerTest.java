package com.mywatchmate.notify;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.MailSendException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@ExtendWith(MockitoExtension.class)
class AfterCommitMailerTest {

    @Mock EmailSender email;

    private final EmailMessage message = new EmailMessage("ada@example.com", "Hi", "Text");

    private AfterCommitMailer mailer() {
        return new AfterCommitMailer(email, Runnable::run);
    }

    @AfterEach
    void clearTransaction() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void insideATransactionItWaitsForTheCommit() {
        TransactionSynchronizationManager.initSynchronization();

        mailer().send(message);
        verify(email, never()).send(any());

        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        verify(email).send(message);
    }

    @Test
    void aRollbackSendsNothing() {
        TransactionSynchronizationManager.initSynchronization();

        mailer().send(message);
        TransactionSynchronizationManager.getSynchronizations()
            .forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

        verify(email, never()).send(any());
    }

    @Test
    void outsideATransactionItGoesStraightAway() {
        mailer().send(message);

        verify(email).send(message);
    }

    @Test
    void aMailFailureIsLoggedNotThrown() {
        doThrow(new MailSendException("down")).when(email).send(message);

        assertThatCode(() -> mailer().send(message)).doesNotThrowAnyException();
    }
}
