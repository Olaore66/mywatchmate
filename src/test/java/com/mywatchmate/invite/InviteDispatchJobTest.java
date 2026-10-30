package com.mywatchmate.invite;

import static com.mywatchmate.testsupport.Fixtures.MONDAY_MIDNIGHT;
import static com.mywatchmate.testsupport.Fixtures.clockAt;
import static com.mywatchmate.testsupport.Fixtures.member;
import static com.mywatchmate.testsupport.Fixtures.space;
import static com.mywatchmate.testsupport.Fixtures.user;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.mywatchmate.invite.SpaceInvitation.Status;
import com.mywatchmate.notify.EmailMessage;
import com.mywatchmate.notify.EmailSender;
import com.mywatchmate.watch.Participant;
import com.mywatchmate.watch.Space;
import com.mywatchmate.watch.SpaceProperties;
import java.time.Duration;
import java.util.Arrays;
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
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

@ExtendWith(MockitoExtension.class)
class InviteDispatchJobTest {

    private static final Duration LIFETIME = Duration.ofDays(14);

    @Mock SpaceInvitationRepository invitations;
    @Mock EmailSender email;
    @Mock PlatformTransactionManager txManager;

    private InviteDispatchJob job;
    private Space space;
    private Participant ada;

    @BeforeEach
    void setUp() {
        lenient().when(txManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        job = new InviteDispatchJob(invitations, email, new SpaceProperties(50, "https://mywatchmates.com"),
            new InviteProperties(20, 100, LIFETIME, Duration.ofMinutes(10), 3, Duration.ofMinutes(1)),
            new TransactionTemplate(txManager), clockAt(MONDAY_MIDNIGHT));
        space = space(user("Ada"));
        ada = member(space, space.getOwner(), Participant.Role.OWNER);
    }

    private SpaceInvitation due(String to) {
        SpaceInvitation i = new SpaceInvitation(space, to, ada, "See you Sunday", MONDAY_MIDNIGHT.minusSeconds(30), LIFETIME);
        ReflectionTestUtils.setField(i, "id", UUID.randomUUID());
        lenient().when(invitations.findById(i.getId())).thenReturn(Optional.of(i));
        return i;
    }

    private void claims(SpaceInvitation... due) {
        List<String> ids = Arrays.stream(due).map(i -> i.getId().toString()).toList();
        when(invitations.lockDue(MONDAY_MIDNIGHT, InviteDispatchJob.BATCH)).thenReturn(ids);
        when(invitations.findWithDetails(any())).thenReturn(List.of(due));
    }

    @Test
    void dueInvitationsAreEmailedWithTheRoomsCurrentLink() {
        SpaceInvitation kemi = due("kemi@example.com");
        claims(kemi);

        assertThat(job.run()).isEqualTo(1);

        ArgumentCaptor<EmailMessage> sent = ArgumentCaptor.forClass(EmailMessage.class);
        verify(email).send(sent.capture());
        assertThat(sent.getValue().to()).isEqualTo("kemi@example.com");
        assertThat(sent.getValue().subject()).startsWith("Ada invited you");
        assertThat(sent.getValue().text()).contains("https://mywatchmates.com/join/" + space.getInviteToken());
        assertThat(sent.getValue().html()).contains("See you Sunday");
        assertThat(kemi.getStatus()).isEqualTo(Status.SENT);
        assertThat(kemi.getAttempts()).isEqualTo(1);
    }

    @Test
    void aFailedSendIsRetriedLaterAndTheOthersStillGo() {
        SpaceInvitation bad = due("bad@example.com");
        SpaceInvitation good = due("good@example.com");
        claims(bad, good);
        doThrow(new MailSendException("Mailbox unavailable")).when(email)
            .send(argThat(m -> m != null && m.to().equals("bad@example.com")));

        assertThat(job.run()).isEqualTo(1);

        assertThat(bad.getStatus()).isEqualTo(Status.PENDING);
        assertThat(bad.getLastError()).isEqualTo("Mailbox unavailable");
        assertThat(bad.getNextAttemptAt()).isEqualTo(MONDAY_MIDNIGHT.plus(Duration.ofMinutes(1)));
        assertThat(good.getStatus()).isEqualTo(Status.SENT);
    }

    @Test
    void archivedRoomsAndExpiredInvitationsAreClosedNotSent() {
        SpaceInvitation expired = due("old@example.com");
        expired.setExpiresAt(MONDAY_MIDNIGHT.minusSeconds(1));
        claims(expired);

        job.run();

        assertThat(expired.getStatus()).isEqualTo(Status.EXPIRED);
        verifyNoInteractions(email);

        SpaceInvitation inArchived = due("x@example.com");
        space.archive(MONDAY_MIDNIGHT);
        when(invitations.lockDue(MONDAY_MIDNIGHT, InviteDispatchJob.BATCH)).thenReturn(List.of(inArchived.getId().toString()));
        when(invitations.findWithDetails(any())).thenReturn(List.of(inArchived));

        job.run();

        assertThat(inArchived.getStatus()).isEqualTo(Status.CANCELLED);
        verifyNoInteractions(email);
    }

    @Test
    void anInvitationWithoutAnInviterIsFromSomeone() {
        SpaceInvitation orphan = due("kemi@example.com");
        orphan.setInvitedBy(null);
        claims(orphan);

        job.run();

        ArgumentCaptor<EmailMessage> sent = ArgumentCaptor.forClass(EmailMessage.class);
        verify(email).send(sent.capture());
        assertThat(sent.getValue().subject()).startsWith("Someone invited you");
    }

    @Test
    void nothingDueNothingSent() {
        when(invitations.lockDue(any(), anyInt())).thenReturn(List.of());

        assertThat(job.run()).isZero();
        verifyNoInteractions(email);
    }

    @Test
    void aTransactionThatReturnsNothingSendsNothing() {
        TransactionTemplate tx = mock(TransactionTemplate.class);
        when(tx.execute(any())).thenReturn(null);
        InviteDispatchJob quiet = new InviteDispatchJob(invitations, email, new SpaceProperties(50, "x"),
            new InviteProperties(20, 100, LIFETIME, Duration.ofMinutes(10), 3, Duration.ofMinutes(1)), tx,
            clockAt(MONDAY_MIDNIGHT));

        assertThat(quiet.run()).isZero();
    }
}
