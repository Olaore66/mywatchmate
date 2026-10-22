package com.mywatchmate.watch;

import static com.mywatchmate.watch.WatchFixtures.assertApiError;
import static com.mywatchmate.watch.WatchFixtures.guest;
import static com.mywatchmate.watch.WatchFixtures.member;
import static com.mywatchmate.watch.WatchFixtures.membership;
import static com.mywatchmate.watch.WatchFixtures.principalOf;
import static com.mywatchmate.watch.WatchFixtures.space;
import static com.mywatchmate.watch.WatchFixtures.user;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.mywatchmate.identity.User;
import com.mywatchmate.identity.UserRepository;
import com.mywatchmate.watch.SpaceAccess.Level;
import com.mywatchmate.watch.SpaceEvent.Type;
import com.mywatchmate.watch.dto.InvitePreviewResponse;
import com.mywatchmate.watch.dto.OwnershipTransferred;
import com.mywatchmate.watch.dto.ParticipantResponse;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

@ExtendWith(MockitoExtension.class)
class MembershipServiceTest {

    private static final int MAX_MEMBERS = 3;

    @Mock SpaceRepository spaces;
    @Mock ParticipantRepository participants;
    @Mock UserRepository users;
    @Mock SpaceAccess access;
    @Mock SpaceViews views;
    @Mock SpaceEvents events;

    private MembershipService service;
    private User ada;
    private Space space;
    private Participant owner;

    @BeforeEach
    void setUp() {
        service = new MembershipService(spaces, participants, users, access, views, events,
            new SpaceProperties(MAX_MEMBERS, "http://localhost:3000"));
        ada = user("Ada");
        space = space(ada);
        owner = member(space, ada, Participant.Role.OWNER);
        lenient().when(spaces.findByInviteToken(space.getInviteToken())).thenReturn(Optional.of(space));
    }

    @Nested
    class Preview {

        @Test
        void showsRoomOwnerCountAndWhatsPlaying() {
            ContentItem video = new ContentItem(space, ContentItem.Source.YOUTUBE, "https://www.youtube.com/watch?v=dQw4w9WgXcQ");
            video.setTitle("Morning Prayer");
            space.playNow(video);
            when(participants.countBySpaceIdAndLeftAtIsNull(space.getId())).thenReturn(2L);

            InvitePreviewResponse preview = service.preview(space.getInviteToken());

            assertThat(preview.spaceTitle()).isEqualTo("6am Prayer Room");
            assertThat(preview.ownerName()).isEqualTo("Ada");
            assertThat(preview.memberCount()).isEqualTo(2);
            assertThat(preview.full()).isFalse();
            assertThat(preview.allowGuests()).isTrue();
            assertThat(preview.nowPlayingTitle()).isEqualTo("Morning Prayer");
        }

        @Test
        void saysWhenTheRoomIsFull() {
            when(participants.countBySpaceIdAndLeftAtIsNull(space.getId())).thenReturn((long) MAX_MEMBERS);
            assertThat(service.preview(space.getInviteToken()).full()).isTrue();
        }

        @Test
        void unknownOrArchivedLinksAreNotFound() {
            when(spaces.findByInviteToken("nope")).thenReturn(Optional.empty());
            assertApiError(() -> service.preview("nope"), HttpStatus.NOT_FOUND, "INVITE_NOT_FOUND");

            space.archive(Instant.now());
            assertApiError(() -> service.preview(space.getInviteToken()), HttpStatus.NOT_FOUND, "INVITE_NOT_FOUND");
        }
    }

    @Nested
    class Joining {

        private final User tunde = user("Tunde");

        @Test
        void newUserBecomesAMemberAndEveryoneIsTold() {
            when(participants.findBySpaceIdAndUserId(space.getId(), tunde.getId())).thenReturn(Optional.empty());
            when(participants.countBySpaceIdAndLeftAtIsNull(space.getId())).thenReturn(1L);
            when(users.getReferenceById(tunde.getId())).thenReturn(tunde);
            when(participants.save(any(Participant.class))).thenAnswer(i -> withId(i.getArgument(0)));

            service.join(tunde.getId(), space.getInviteToken());

            ArgumentCaptor<Participant> saved = ArgumentCaptor.forClass(Participant.class);
            verify(participants).save(saved.capture());
            assertThat(saved.getValue().getUser()).isSameAs(tunde);
            assertThat(saved.getValue().getRole()).isEqualTo(Participant.Role.MEMBER);
            assertThat(saved.getValue().getSpace()).isSameAs(space);
            verify(events).publish(eq(space.getId()), eq(Type.MEMBER_JOINED),
                argThat(data -> ((ParticipantResponse) data).displayName().equals("Tunde")));
            verify(views).of(argThat(m -> m.level() == Level.MEMBER));
        }

        @Test
        void joiningAgainWhileInTheRoomChangesNothing() {
            Participant existing = member(space, tunde, Participant.Role.ADMIN);
            when(participants.findBySpaceIdAndUserId(space.getId(), tunde.getId())).thenReturn(Optional.of(existing));

            service.join(tunde.getId(), space.getInviteToken());

            verify(participants, never()).save(any());
            verifyNoInteractions(events);
            verify(views).of(argThat(m -> m.level() == Level.ADMIN && m.participant() == existing));
        }

        @Test
        void comingBackAfterLeavingRejoinsAsAPlainMember() {
            Participant left = member(space, tunde, Participant.Role.ADMIN);
            left.leave(Instant.now().minusSeconds(3600));
            when(participants.findBySpaceIdAndUserId(space.getId(), tunde.getId())).thenReturn(Optional.of(left));
            when(participants.countBySpaceIdAndLeftAtIsNull(space.getId())).thenReturn(1L);

            service.join(tunde.getId(), space.getInviteToken());

            assertThat(left.isActive()).isTrue();
            assertThat(left.getRole()).isEqualTo(Participant.Role.MEMBER);
            verify(participants, never()).save(any());
            verify(events).publish(eq(space.getId()), eq(Type.MEMBER_JOINED), any());
        }

        @Test
        void removedPeopleCantRejoinFromTheLink() {
            Participant removed = member(space, tunde, Participant.Role.MEMBER);
            removed.remove(Instant.now(), owner);
            when(participants.findBySpaceIdAndUserId(space.getId(), tunde.getId())).thenReturn(Optional.of(removed));

            assertApiError(() -> service.join(tunde.getId(), space.getInviteToken()), HttpStatus.FORBIDDEN, "REMOVED_FROM_SPACE");
            verifyNoInteractions(events);
        }

        @Test
        void fullRoomRefusesNewPeople() {
            when(participants.findBySpaceIdAndUserId(space.getId(), tunde.getId())).thenReturn(Optional.empty());
            when(participants.countBySpaceIdAndLeftAtIsNull(space.getId())).thenReturn((long) MAX_MEMBERS);

            assertApiError(() -> service.join(tunde.getId(), space.getInviteToken()), HttpStatus.CONFLICT, "SPACE_FULL");
            verify(participants, never()).save(any());
        }

        @Test
        void guestIsAdmittedWithNameAndTokenHash() {
            when(participants.countBySpaceIdAndLeftAtIsNull(space.getId())).thenReturn(1L);
            when(participants.save(any(Participant.class))).thenAnswer(i -> withId(i.getArgument(0)));

            Participant bisi = service.admitGuest(space.getInviteToken(), "Bisi", "hash:device");

            assertThat(bisi.isGuest()).isTrue();
            assertThat(bisi.getGuestDisplayName()).isEqualTo("Bisi");
            assertThat(bisi.getGuestTokenHash()).isEqualTo("hash:device");
            verify(events).publish(eq(space.getId()), eq(Type.MEMBER_JOINED), any());
        }

        @Test
        void membersOnlyRoomRefusesGuests() {
            space.setAllowGuests(false);
            assertApiError(() -> service.admitGuest(space.getInviteToken(), "Bisi", "h"), HttpStatus.FORBIDDEN, "GUESTS_NOT_ALLOWED");
            verify(participants, never()).save(any());
        }

        @Test
        void fullRoomRefusesGuestsToo() {
            when(participants.countBySpaceIdAndLeftAtIsNull(space.getId())).thenReturn((long) MAX_MEMBERS);
            assertApiError(() -> service.admitGuest(space.getInviteToken(), "Bisi", "h"), HttpStatus.CONFLICT, "SPACE_FULL");
        }

        @Test
        void archivedRoomRefusesGuests() {
            space.archive(Instant.now());
            assertApiError(() -> service.admitGuest(space.getInviteToken(), "Bisi", "h"), HttpStatus.NOT_FOUND, "INVITE_NOT_FOUND");
        }
    }

    @Nested
    class Roles {

        private Participant tunde;

        @BeforeEach
        void people() {
            tunde = member(space, user("Tunde"), Participant.Role.MEMBER);
            lenient().when(access.require(principalOf(owner), space.getId())).thenReturn(membership(owner));
            lenient().when(participants.findById(tunde.getId())).thenReturn(Optional.of(tunde));
        }

        @Test
        void ownerPromotesAMemberToAdmin() {
            ParticipantResponse response = service.changeRole(principalOf(owner), space.getId(), tunde.getId(), Participant.Role.ADMIN);

            assertThat(tunde.getRole()).isEqualTo(Participant.Role.ADMIN);
            assertThat(response.role()).isEqualTo(Participant.Role.ADMIN);
            verify(events).publish(space.getId(), Type.ROLE_CHANGED, response);
        }

        @Test
        void ownershipCantBeGivenThroughRoleChange() {
            assertApiError(() -> service.changeRole(principalOf(owner), space.getId(), tunde.getId(), Participant.Role.OWNER),
                HttpStatus.BAD_REQUEST, "INVALID_ROLE");
        }

        @Test
        void ownerCantChangeTheirOwnRole() {
            when(participants.findById(owner.getId())).thenReturn(Optional.of(owner));
            assertApiError(() -> service.changeRole(principalOf(owner), space.getId(), owner.getId(), Participant.Role.MEMBER),
                HttpStatus.BAD_REQUEST, "CANNOT_TARGET_SELF");
        }

        @Test
        void guestsCantBecomeAdmins() {
            Participant bisi = guest(space, "Bisi");
            when(participants.findById(bisi.getId())).thenReturn(Optional.of(bisi));
            assertApiError(() -> service.changeRole(principalOf(owner), space.getId(), bisi.getId(), Participant.Role.ADMIN),
                HttpStatus.BAD_REQUEST, "GUEST_CANNOT_BE_ADMIN");
        }

        @Test
        void adminsCantChangeRoles() {
            Participant admin = member(space, user("Admin"), Participant.Role.ADMIN);
            when(access.require(principalOf(admin), space.getId())).thenReturn(membership(admin));
            assertApiError(() -> service.changeRole(principalOf(admin), space.getId(), tunde.getId(), Participant.Role.ADMIN),
                HttpStatus.FORBIDDEN, "FORBIDDEN_ROLE");
        }

        @Test
        void targetMustBeInThisRoomNow() {
            Participant elsewhere = member(space(user("Other")), user("Kemi"), Participant.Role.MEMBER);
            when(participants.findById(elsewhere.getId())).thenReturn(Optional.of(elsewhere));
            assertApiError(() -> service.changeRole(principalOf(owner), space.getId(), elsewhere.getId(), Participant.Role.ADMIN),
                HttpStatus.NOT_FOUND, "PARTICIPANT_NOT_FOUND");

            tunde.leave(Instant.now());
            assertApiError(() -> service.changeRole(principalOf(owner), space.getId(), tunde.getId(), Participant.Role.ADMIN),
                HttpStatus.NOT_FOUND, "PARTICIPANT_NOT_FOUND");
        }

        @Test
        void ownerHandsTheRoomOverAndBecomesAdmin() {
            service.transferOwnership(principalOf(owner), space.getId(), tunde.getId());

            assertThat(tunde.getRole()).isEqualTo(Participant.Role.OWNER);
            assertThat(owner.getRole()).isEqualTo(Participant.Role.ADMIN);
            assertThat(space.getOwner()).isSameAs(tunde.getUser());
            ArgumentCaptor<Object> data = ArgumentCaptor.forClass(Object.class);
            verify(events).publish(eq(space.getId()), eq(Type.OWNERSHIP_TRANSFERRED), data.capture());
            OwnershipTransferred transferred = (OwnershipTransferred) data.getValue();
            assertThat(transferred.previousOwner().id()).isEqualTo(owner.getId());
            assertThat(transferred.newOwner().id()).isEqualTo(tunde.getId());
            verify(views).of(argThat(m -> m.level() == Level.ADMIN));
        }

        @Test
        void ownershipCantGoToAGuestOrToYourself() {
            Participant bisi = guest(space, "Bisi");
            when(participants.findById(bisi.getId())).thenReturn(Optional.of(bisi));
            when(participants.findById(owner.getId())).thenReturn(Optional.of(owner));

            assertApiError(() -> service.transferOwnership(principalOf(owner), space.getId(), bisi.getId()),
                HttpStatus.BAD_REQUEST, "GUEST_CANNOT_BE_OWNER");
            assertApiError(() -> service.transferOwnership(principalOf(owner), space.getId(), owner.getId()),
                HttpStatus.BAD_REQUEST, "CANNOT_TARGET_SELF");
            assertThat(owner.getRole()).isEqualTo(Participant.Role.OWNER);
        }
    }

    @Nested
    class RemovingAndLeaving {

        private Participant admin;
        private Participant tunde;
        private Participant bisi;

        @BeforeEach
        void people() {
            admin = member(space, user("Admin"), Participant.Role.ADMIN);
            tunde = member(space, user("Tunde"), Participant.Role.MEMBER);
            bisi = guest(space, "Bisi");
            for (Participant p : List.of(owner, admin, tunde, bisi)) {
                lenient().when(access.require(principalOf(p), space.getId())).thenReturn(membership(p));
                lenient().when(participants.findById(p.getId())).thenReturn(Optional.of(p));
            }
        }

        @Test
        void ownerRemovesAnAdmin() {
            service.remove(principalOf(owner), space.getId(), admin.getId());

            assertThat(admin.isActive()).isFalse();
            assertThat(admin.wasRemoved()).isTrue();
            assertThat(admin.getRemovedBy()).isSameAs(owner);
            verify(events).publish(eq(space.getId()), eq(Type.MEMBER_REMOVED), any());
        }

        @Test
        void adminRemovesMembersAndGuests() {
            service.remove(principalOf(admin), space.getId(), tunde.getId());
            service.remove(principalOf(admin), space.getId(), bisi.getId());

            assertThat(tunde.wasRemoved()).isTrue();
            assertThat(bisi.wasRemoved()).isTrue();
        }

        @Test
        void adminsCantRemoveTheOwnerOrOtherAdmins() {
            Participant otherAdmin = member(space, user("Kemi"), Participant.Role.ADMIN);
            when(participants.findById(otherAdmin.getId())).thenReturn(Optional.of(otherAdmin));

            assertApiError(() -> service.remove(principalOf(admin), space.getId(), owner.getId()), HttpStatus.FORBIDDEN, "FORBIDDEN_ROLE");
            assertApiError(() -> service.remove(principalOf(admin), space.getId(), otherAdmin.getId()), HttpStatus.FORBIDDEN, "FORBIDDEN_ROLE");
            assertThat(owner.isActive()).isTrue();
            assertThat(otherAdmin.isActive()).isTrue();
        }

        @Test
        void membersAndGuestsCantRemoveAnyone() {
            assertApiError(() -> service.remove(principalOf(tunde), space.getId(), bisi.getId()), HttpStatus.FORBIDDEN, "FORBIDDEN_ROLE");
            assertApiError(() -> service.remove(principalOf(bisi), space.getId(), tunde.getId()), HttpStatus.FORBIDDEN, "FORBIDDEN_ROLE");
        }

        @Test
        void removingYourselfIsNotHowYouLeave() {
            assertApiError(() -> service.remove(principalOf(admin), space.getId(), admin.getId()),
                HttpStatus.BAD_REQUEST, "CANNOT_TARGET_SELF");
        }

        @Test
        void membersAndGuestsCanLeave() {
            service.leave(principalOf(tunde), space.getId());
            service.leave(principalOf(bisi), space.getId());

            assertThat(tunde.isActive()).isFalse();
            assertThat(tunde.wasRemoved()).isFalse();
            assertThat(bisi.isActive()).isFalse();
            verify(events, org.mockito.Mockito.times(2)).publish(eq(space.getId()), eq(Type.MEMBER_LEFT), any());
        }

        @Test
        void ownerMustTransferBeforeLeaving() {
            assertApiError(() -> service.leave(principalOf(owner), space.getId()), HttpStatus.CONFLICT, "OWNER_MUST_TRANSFER");
            assertThat(owner.isActive()).isTrue();
        }

        @Test
        void listingShowsEveryoneInTheRoom() {
            when(participants.findActiveWithUsers(space.getId())).thenReturn(List.of(owner, admin, tunde, bisi));

            List<ParticipantResponse> list = service.list(principalOf(bisi), space.getId());

            assertThat(list).extracting(ParticipantResponse::displayName).containsExactly("Ada", "Admin", "Tunde", "Bisi");
            assertThat(list).extracting(ParticipantResponse::guest).containsExactly(false, false, false, true);
        }
    }

    private static Participant withId(Participant participant) {
        participant.setId(UUID.randomUUID());
        return participant;
    }
}
