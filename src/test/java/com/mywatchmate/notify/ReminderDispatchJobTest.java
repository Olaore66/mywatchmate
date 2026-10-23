package com.mywatchmate.notify;

import static com.mywatchmate.testsupport.Fixtures.clockAt;
import static com.mywatchmate.testsupport.Fixtures.member;
import static com.mywatchmate.testsupport.Fixtures.session;
import static com.mywatchmate.testsupport.Fixtures.space;
import static com.mywatchmate.testsupport.Fixtures.user;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.mywatchmate.track.Reminder;
import com.mywatchmate.track.ReminderRepository;
import com.mywatchmate.watch.Participant;
import com.mywatchmate.watch.ParticipantRepository;
import com.mywatchmate.watch.Space;
import com.mywatchmate.watch.SpaceProperties;
import com.mywatchmate.watch.WatchSession;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.MailSendException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@ExtendWith(MockitoExtension.class)
class ReminderDispatchJobTest {

    private static final Instant SIX_AM_LAGOS = Instant.parse("2026-11-02T05:00:00Z");
    private static final Instant NOW = SIX_AM_LAGOS.minusSeconds(600);

    @Mock ReminderRepository reminders;
    @Mock ParticipantRepository participants;
    @Mock EmailSender email;

    private ReminderDispatchJob job;
    private Space space;
    private Participant tunde;
    private WatchSession prayer;
    private Reminder reminder;

    @BeforeEach
    void setUp() {
        job = new ReminderDispatchJob(reminders, participants, email, new SpaceProperties(50, "http://localhost:3000"),
            new TransactionTemplate(mock(PlatformTransactionManager.class)), clockAt(NOW));
        space = space(user("Ada"));
        tunde = member(space, user("Tunde"), Participant.Role.MEMBER);
        tunde.getUser().setTimezone("Europe/London");
        prayer = session(space, SIX_AM_LAGOS);
        reminder = new Reminder(tunde.getUser(), prayer, Reminder.Channel.EMAIL, NOW);
        reminder.setId(UUID.randomUUID());
        lenient().when(reminders.lockDue(NOW, ReminderDispatchJob.BATCH)).thenReturn(List.of(reminder.getId()));
        lenient().when(reminders.findWithDetails(List.of(reminder.getId()))).thenReturn(List.of(reminder));
        lenient().when(participants.findBySpaceIdAndUserId(space.getId(), tunde.getUser().getId())).thenReturn(Optional.of(tunde));
    }

    @Test
    void dueReminderIsMarkedSentAndEmailed() {
        assertThat(job.run()).isEqualTo(1);

        assertThat(reminder.getStatus()).isEqualTo(Reminder.Status.SENT);
        assertThat(reminder.getSentAt()).isEqualTo(NOW);
        ArgumentCaptor<EmailMessage> sent = ArgumentCaptor.forClass(EmailMessage.class);
        verify(email).send(sent.capture());
        assertThat(sent.getValue().to()).isEqualTo("tunde@example.com");
        assertThat(sent.getValue().subject()).isEqualTo("Starting in 10 minutes: 6am Prayer Room");
        assertThat(sent.getValue().text()).contains("Mon 2 Nov, 05:00 (Europe/London)", "http://localhost:3000/spaces/" + space.getId());
    }

    @Test
    void nothingDueSendsNothing() {
        when(reminders.lockDue(NOW, ReminderDispatchJob.BATCH)).thenReturn(List.of());

        assertThat(job.run()).isZero();
        verify(reminders, never()).findWithDetails(any());
        verifyNoInteractions(email);
    }

    @Test
    void accountWideSwitchOffCancelsInsteadOfSending() {
        tunde.getUser().setEmailReminders(false);
        assertCancelledNotSent();
    }

    @Test
    void roomSettingOffCancels() {
        tunde.setReminderMinutesBefore(null);
        assertCancelledNotSent();
    }

    @Test
    void personWhoLeftIsNotEmailed() {
        tunde.leave(NOW.minusSeconds(60));
        assertCancelledNotSent();
    }

    @Test
    void cancelledSessionIsNotEmailed() {
        prayer.cancel();
        assertCancelledNotSent();
    }

    @Test
    void archivedRoomIsNotEmailed() {
        space.archive(NOW.minusSeconds(60));
        assertCancelledNotSent();
    }

    @Test
    void sessionThatAlreadyStartedStillGetsItsReminder() {
        prayer.start(NOW);

        assertThat(job.run()).isEqualTo(1);
        ArgumentCaptor<EmailMessage> sent = ArgumentCaptor.forClass(EmailMessage.class);
        verify(email).send(sent.capture());
        assertThat(sent.getValue().subject()).startsWith("Happening now");
    }

    @Test
    void mailServerFailureMarksTheReminderFailedWithTheReason() {
        doThrow(new MailSendException("Connection refused")).when(email).send(any());
        when(reminders.findById(reminder.getId())).thenReturn(Optional.of(reminder));

        assertThat(job.run()).isZero();

        assertThat(reminder.getStatus()).isEqualTo(Reminder.Status.FAILED);
        assertThat(reminder.getLastError()).isEqualTo("Connection refused");
    }

    private void assertCancelledNotSent() {
        assertThat(job.run()).isZero();
        assertThat(reminder.getStatus()).isEqualTo(Reminder.Status.CANCELLED);
        verifyNoInteractions(email);
    }
}
