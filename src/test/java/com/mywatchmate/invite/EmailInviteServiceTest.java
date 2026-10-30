package com.mywatchmate.invite;

import static com.mywatchmate.testsupport.Fixtures.MONDAY_MIDNIGHT;
import static com.mywatchmate.testsupport.Fixtures.assertApiError;
import static com.mywatchmate.testsupport.Fixtures.clockAt;
import static com.mywatchmate.testsupport.Fixtures.guest;
import static com.mywatchmate.testsupport.Fixtures.member;
import static com.mywatchmate.testsupport.Fixtures.membership;
import static com.mywatchmate.testsupport.Fixtures.principalOf;
import static com.mywatchmate.testsupport.Fixtures.space;
import static com.mywatchmate.testsupport.Fixtures.user;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.mywatchmate.invite.SpaceInvitation.Status;
import com.mywatchmate.invite.dto.EmailInviteRequest;
import com.mywatchmate.invite.dto.EmailInviteResponse;
import com.mywatchmate.invite.dto.EmailInviteResult;
import com.mywatchmate.invite.dto.EmailInviteResult.Outcome;
import com.mywatchmate.invite.dto.InvitationResponse;
import com.mywatchmate.watch.Participant;
import com.mywatchmate.watch.ParticipantRepository;
import com.mywatchmate.watch.Space;
import com.mywatchmate.watch.SpaceAccess;
import com.mywatchmate.watch.SpaceEvent;
import com.mywatchmate.watch.dto.ParticipantResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class EmailInviteServiceTest {

    private static final Instant NOW = MONDAY_MIDNIGHT;
    private static final Duration LIFETIME = Duration.ofDays(14);

    @Mock SpaceInvitationRepository invitations;
    @Mock ParticipantRepository participants;
    @Mock SpaceAccess access;

    private final InviteProperties props = new InviteProperties(20, 100, LIFETIME, Duration.ofMinutes(10), 3, Duration.ofMinutes(1));
    private EmailInviteService service;
    private Space space;
    private Participant ada;
    private Participant femi;
    private Participant tunde;
    private Participant bisi;

    @BeforeEach
    void setUp() {
        service = new EmailInviteService(invitations, participants, access, props, clockAt(NOW));
        space = space(user("Ada"));
        ada = member(space, space.getOwner(), Participant.Role.OWNER);
        femi = member(space, user("Femi"), Participant.Role.ADMIN);
        tunde = member(space, user("Tunde"), Participant.Role.MEMBER);
        bisi = guest(space, "Bisi");
        for (Participant p : List.of(ada, femi, tunde, bisi)) {
            lenient().when(access.require(principalOf(p), space.getId())).thenReturn(membership(p));
        }
        lenient().when(participants.findActiveWithUsers(space.getId())).thenReturn(List.of(ada, femi, tunde, bisi));
        lenient().when(invitations.save(any(SpaceInvitation.class))).thenAnswer(i -> withId(i.getArgument(0)));
        lenient().when(invitations.findWaiting(any(), any())).thenReturn(List.of());
    }

    private static SpaceInvitation withId(SpaceInvitation invitation) {
        if (invitation.getId() == null) {
            ReflectionTestUtils.setField(invitation, "id", UUID.randomUUID());
        }
        return invitation;
    }

    private SpaceInvitation existing(String email, Instant queuedAt) {
        SpaceInvitation i = withId(new SpaceInvitation(space, email, ada, null, queuedAt, LIFETIME));
        lenient().when(invitations.findByIdAndSpaceId(i.getId(), space.getId())).thenReturn(Optional.of(i));
        return i;
    }

    private EmailInviteResponse invite(Participant who, String... emails) {
        return service.invite(principalOf(who), space.getId(), new EmailInviteRequest(List.of(emails), null));
    }

    @Nested
    class Inviting {

        @Test
        void eachNewAddressIsQueuedForTheJobToSend() {
            EmailInviteResponse response = service.invite(principalOf(femi), space.getId(),
                new EmailInviteRequest(List.of("  Kemi@Example.com ", "funmi@example.com"), "See you Sunday!"));

            assertThat(response.queued()).isEqualTo(2);
            assertThat(response.results()).extracting(EmailInviteResult::email, EmailInviteResult::status)
                .containsExactly(tuple("kemi@example.com", Outcome.QUEUED),
                    tuple("funmi@example.com", Outcome.QUEUED));
            ArgumentCaptor<SpaceInvitation> saved = ArgumentCaptor.forClass(SpaceInvitation.class);
            verify(invitations, times(2)).save(saved.capture());
            SpaceInvitation first = saved.getAllValues().getFirst();
            assertThat(first.getInvitedBy()).isSameAs(femi);
            assertThat(first.getMessage()).isEqualTo("See you Sunday!");
            assertThat(first.getStatus()).isEqualTo(Status.PENDING);
            assertThat(response.results().getFirst().invitationId()).isEqualTo(first.getId());
        }

        @Test
        void membersBadAddressesAndRepeatsAreReportedNotSent() {
            EmailInviteResponse response = invite(ada, "TUNDE@example.com", "not-an-email", "kemi@example.com",
                "Kemi@example.com", "a@b", "");

            assertThat(response.results()).extracting(EmailInviteResult::email, EmailInviteResult::status).containsExactly(
                tuple("tunde@example.com", Outcome.ALREADY_MEMBER),
                tuple("not-an-email", Outcome.INVALID_EMAIL),
                tuple("kemi@example.com", Outcome.QUEUED),
                tuple("a@b", Outcome.INVALID_EMAIL),
                tuple("", Outcome.INVALID_EMAIL));
            assertThat(response.queued()).isEqualTo(1);
        }

        @Test
        void someoneAlreadyWaitingIsNotInvitedTwice() {
            SpaceInvitation waiting = existing("kemi@example.com", NOW.minusSeconds(60));
            when(invitations.findWaiting(space.getId(), List.of("kemi@example.com"))).thenReturn(List.of(waiting));

            EmailInviteResult result = invite(ada, "kemi@example.com").results().getFirst();

            assertThat(result.status()).isEqualTo(Outcome.ALREADY_INVITED);
            assertThat(result.invitationId()).isEqualTo(waiting.getId());
            verify(invitations, never()).save(any());
        }

        @Test
        void anExpiredInvitationIsClosedFirstThenReplaced() {
            SpaceInvitation old = existing("kemi@example.com", NOW.minus(LIFETIME).minusSeconds(1));
            when(invitations.findWaiting(space.getId(), List.of("kemi@example.com"))).thenReturn(List.of(old));

            EmailInviteResult result = invite(ada, "kemi@example.com").results().getFirst();

            assertThat(result.status()).isEqualTo(Outcome.QUEUED);
            assertThat(old.getStatus()).isEqualTo(Status.EXPIRED);
            InOrder order = inOrder(invitations);
            order.verify(invitations).flush();
            order.verify(invitations).save(any(SpaceInvitation.class));
        }

        @Test
        void theRoomsDailyLimitStopsTheRest() {
            when(invitations.countBySpaceIdAndLastQueuedAtAfter(space.getId(), NOW.minus(Duration.ofDays(1)))).thenReturn(99L);

            EmailInviteResponse response = invite(ada, "one@example.com", "two@example.com", "three@example.com");

            assertThat(response.results()).extracting(EmailInviteResult::status)
                .containsExactly(Outcome.QUEUED, Outcome.RATE_LIMITED, Outcome.RATE_LIMITED);
            assertThat(response.queued()).isEqualTo(1);
        }

        @Test
        void ownerAndAdminsOnlyAndNotInArchivedRooms() {
            assertApiError(() -> invite(tunde, "kemi@example.com"), HttpStatus.FORBIDDEN, "FORBIDDEN_ROLE");
            assertApiError(() -> invite(bisi, "kemi@example.com"), HttpStatus.FORBIDDEN, "FORBIDDEN_ROLE");
            space.archive(NOW);
            assertApiError(() -> invite(ada, "kemi@example.com"), HttpStatus.CONFLICT, "SPACE_ARCHIVED");
            verifyNoInteractions(invitations);
        }
    }

    @Nested
    class TheNote {

        @Test
        void linksAreRemovedAndTheTextTidied() {
            assertThat(EmailInviteService.cleanNote("Join us! https://evil.example/x and www.phish.com too"))
                .isEqualTo("Join us! [link removed] and [link removed] too");
            assertThat(EmailInviteService.cleanNote("Hi\r\n\n\n\n\nSee you\u0007 soon  ")).isEqualTo("Hi\n\nSee you soon");
        }

        @Test
        void emptyNotesAreNoNoteAndLongOnesAreCut() {
            assertThat(EmailInviteService.cleanNote(null)).isNull();
            assertThat(EmailInviteService.cleanNote("   \n  ")).isNull();
            assertThat(EmailInviteService.cleanNote("www.a " + "x".repeat(495))).hasSizeLessThanOrEqualTo(EmailInviteService.MAX_NOTE);
        }

        @Test
        void addressesAreTrimmedLowerCasedAndChecked() {
            assertThat(EmailInviteService.normalise("  Kemi@Example.COM ")).isEqualTo("kemi@example.com");
            assertThat(EmailInviteService.normalise(null)).isEmpty();
            assertThat(EmailInviteService.isEmail("kemi@example.com")).isTrue();
            assertThat(EmailInviteService.isEmail("kemi example@x.com")).isFalse();
            assertThat(EmailInviteService.isEmail("kemi@x.c")).isFalse();
            assertThat(EmailInviteService.isEmail("x".repeat(250) + "@x.com")).isFalse();
        }
    }

    @Nested
    class Managing {

        @Test
        void hostsSeeEveryoneInvited() {
            SpaceInvitation one = existing("kemi@example.com", NOW);
            when(invitations.findForSpace(space.getId())).thenReturn(List.of(one));

            List<InvitationResponse> list = service.list(principalOf(ada), space.getId());

            assertThat(list).extracting(InvitationResponse::email).containsExactly("kemi@example.com");
            assertApiError(() -> service.list(principalOf(tunde), space.getId()), HttpStatus.FORBIDDEN, "FORBIDDEN_ROLE");
        }

        @Test
        void resendingQueuesItAgainWithAFreshExpiry() {
            SpaceInvitation sent = existing("kemi@example.com", NOW.minus(Duration.ofHours(2)));
            sent.sending(NOW.minus(Duration.ofHours(2)));

            InvitationResponse response = service.resend(principalOf(femi), space.getId(), sent.getId());

            assertThat(response.status()).isEqualTo(Status.PENDING);
            assertThat(sent.getNextAttemptAt()).isEqualTo(NOW);
            assertThat(sent.getExpiresAt()).isEqualTo(NOW.plus(LIFETIME));
        }

        @Test
        void notTooSoonNotWhenClosedNotForMembers() {
            SpaceInvitation fresh = existing("kemi@example.com", NOW.minusSeconds(60));
            assertApiError(() -> service.resend(principalOf(ada), space.getId(), fresh.getId()), HttpStatus.CONFLICT, "RESEND_TOO_SOON");

            SpaceInvitation joined = existing("x@example.com", NOW.minus(Duration.ofHours(1)));
            joined.joined(NOW);
            assertApiError(() -> service.resend(principalOf(ada), space.getId(), joined.getId()), HttpStatus.CONFLICT, "INVITATION_CLOSED");

            SpaceInvitation toMember = existing("tunde@example.com", NOW.minus(Duration.ofHours(1)));
            assertApiError(() -> service.resend(principalOf(ada), space.getId(), toMember.getId()), HttpStatus.CONFLICT, "ALREADY_MEMBER");
        }

        @Test
        void anOldInvitationCantBeResentWhenANewerOneIsWaiting() {
            SpaceInvitation old = existing("kemi@example.com", NOW.minus(Duration.ofDays(20)));
            old.expire();
            SpaceInvitation newer = existing("kemi@example.com", NOW.minus(Duration.ofHours(1)));
            when(invitations.findWaiting(space.getId(), List.of("kemi@example.com"))).thenReturn(List.of(newer));

            assertApiError(() -> service.resend(principalOf(ada), space.getId(), old.getId()), HttpStatus.CONFLICT, "ALREADY_INVITED");
        }

        @Test
        void resendsCountTowardsTheDailyLimit() {
            SpaceInvitation failed = existing("kemi@example.com", NOW.minus(Duration.ofHours(1)));
            failed.sending(NOW.minus(Duration.ofHours(1)));
            failed.sendFailed("x", NOW.minus(Duration.ofHours(1)), 1, Duration.ofMinutes(1));
            when(invitations.countBySpaceIdAndLastQueuedAtAfter(space.getId(), NOW.minus(Duration.ofDays(1)))).thenReturn(100L);

            assertApiError(() -> service.resend(principalOf(ada), space.getId(), failed.getId()),
                HttpStatus.TOO_MANY_REQUESTS, "INVITE_LIMIT_REACHED");
        }

        @Test
        void cancellingWaitingOnesOnly() {
            SpaceInvitation waiting = existing("kemi@example.com", NOW);
            SpaceInvitation joined = existing("x@example.com", NOW);
            joined.joined(NOW);

            service.cancel(principalOf(ada), space.getId(), waiting.getId());
            service.cancel(principalOf(ada), space.getId(), joined.getId());

            assertThat(waiting.getStatus()).isEqualTo(Status.CANCELLED);
            assertThat(joined.getStatus()).isEqualTo(Status.JOINED);
        }

        @Test
        void anInvitationFromAnotherRoomIsNotFound() {
            UUID elsewhere = UUID.randomUUID();
            when(invitations.findByIdAndSpaceId(elsewhere, space.getId())).thenReturn(Optional.empty());

            assertApiError(() -> service.cancel(principalOf(ada), space.getId(), elsewhere), HttpStatus.NOT_FOUND, "INVITATION_NOT_FOUND");
            assertApiError(() -> service.resend(principalOf(ada), space.getId(), elsewhere), HttpStatus.NOT_FOUND, "INVITATION_NOT_FOUND");
        }
    }

    @Nested
    class Joining {

        @Test
        void someoneInvitedJoiningMarksTheirInvitationJoined() {
            Participant kemi = member(space, user("Kemi"), Participant.Role.MEMBER);
            kemi.getUser().setEmail("Kemi@Example.com");
            when(participants.findById(kemi.getId())).thenReturn(Optional.of(kemi));
            SpaceInvitation waiting = existing("kemi@example.com", NOW.minusSeconds(3600));
            when(invitations.findWaiting(space.getId(), List.of("kemi@example.com"))).thenReturn(List.of(waiting));

            service.onSpaceEvent(new SpaceEvent(SpaceEvent.Type.MEMBER_JOINED, space.getId(), NOW, ParticipantResponse.from(kemi)));

            assertThat(waiting.getStatus()).isEqualTo(Status.JOINED);
            assertThat(waiting.getJoinedAt()).isEqualTo(NOW);
        }

        @Test
        void guestsAndOtherEventsAreIgnored() {
            service.onSpaceEvent(new SpaceEvent(SpaceEvent.Type.MEMBER_JOINED, space.getId(), NOW, ParticipantResponse.from(bisi)));
            service.onSpaceEvent(new SpaceEvent(SpaceEvent.Type.MEMBER_LEFT, space.getId(), NOW, ParticipantResponse.from(tunde)));
            service.onSpaceEvent(new SpaceEvent(SpaceEvent.Type.MEMBER_JOINED, space.getId(), NOW, null));

            verifyNoInteractions(invitations);
        }
    }
}
