package com.mywatchmate.watch;

import static com.mywatchmate.watch.WatchFixtures.assertApiError;
import static com.mywatchmate.watch.WatchFixtures.guest;
import static com.mywatchmate.watch.WatchFixtures.member;
import static com.mywatchmate.watch.WatchFixtures.membership;
import static com.mywatchmate.watch.WatchFixtures.principalOf;
import static com.mywatchmate.watch.WatchFixtures.space;
import static com.mywatchmate.watch.WatchFixtures.user;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.mywatchmate.common.ApiException;
import com.mywatchmate.identity.User;
import com.mywatchmate.identity.UserRepository;
import com.mywatchmate.watch.SpaceAccess.Level;
import com.mywatchmate.watch.SpaceEvent.Type;
import com.mywatchmate.watch.content.PreparedVideo;
import com.mywatchmate.watch.content.ResolvedLink;
import com.mywatchmate.watch.content.VideoMetadata;
import com.mywatchmate.watch.dto.CreateSpaceRequest;
import com.mywatchmate.watch.dto.InviteResponse;
import com.mywatchmate.watch.dto.SpaceInfo;
import com.mywatchmate.watch.dto.UpdateSpaceRequest;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@ExtendWith(MockitoExtension.class)
class SpaceServiceTest {

    @Mock UserRepository users;
    @Mock SpaceRepository spaces;
    @Mock ParticipantRepository participants;
    @Mock SpaceAccess access;
    @Mock SpaceViews views;
    @Mock InviteTokens inviteTokens;
    @Mock ContentService content;
    @Mock SpaceEvents events;

    private final PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
    private SpaceService service;
    private User ada;

    @BeforeEach
    void setUp() {
        service = new SpaceService(users, spaces, participants, access, views, inviteTokens, content, events,
            new TransactionTemplate(txManager));
        ada = user("Ada");
    }

    @Nested
    class Creating {

        @BeforeEach
        void stubs() {
            lenient().when(users.findById(ada.getId())).thenReturn(Optional.of(ada));
            lenient().when(inviteTokens.newToken()).thenReturn("fresh-invite-123");
            lenient().when(participants.save(any(Participant.class))).thenAnswer(i -> i.getArgument(0));
        }

        @Test
        void creatorBecomesOwnerWithSensibleDefaults() {
            service.create(ada.getId(), new CreateSpaceRequest("  6am Prayer  ", "   ", Space.AudienceType.DIASPORA,
                null, null, null));

            ArgumentCaptor<Space> savedSpace = ArgumentCaptor.forClass(Space.class);
            verify(spaces).save(savedSpace.capture());
            Space space = savedSpace.getValue();
            assertThat(space.getTitle()).isEqualTo("6am Prayer");
            assertThat(space.getDescription()).isNull();
            assertThat(space.getOwner()).isSameAs(ada);
            assertThat(space.getInviteToken()).isEqualTo("fresh-invite-123");
            assertThat(space.isAllowGuests()).isTrue();
            assertThat(space.isMembersCanAddContent()).isFalse();
            assertThat(space.getCurrentContent()).isNull();

            ArgumentCaptor<Participant> savedOwner = ArgumentCaptor.forClass(Participant.class);
            verify(participants).save(savedOwner.capture());
            assertThat(savedOwner.getValue().getRole()).isEqualTo(Participant.Role.OWNER);
            assertThat(savedOwner.getValue().getUser()).isSameAs(ada);
            verify(views).of(argThat(m -> m.level() == Level.OWNER));
            verifyNoInteractions(content, events);
        }

        @Test
        void firstVideoIsLookedUpBeforeTheTransactionAndStartsPlaying() {
            PreparedVideo prepared = new PreparedVideo(
                new ResolvedLink(ContentItem.Source.YOUTUBE, "https://www.youtube.com/watch?v=dQw4w9WgXcQ", "dQw4w9WgXcQ", false),
                VideoMetadata.UNKNOWN);
            ContentItem saved = new ContentItem(null, ContentItem.Source.YOUTUBE, "https://www.youtube.com/watch?v=dQw4w9WgXcQ");
            when(content.prepare("https://youtu.be/dQw4w9WgXcQ")).thenReturn(prepared);
            when(content.save(any(), eq(prepared))).thenReturn(saved);

            service.create(ada.getId(), new CreateSpaceRequest("Room", null, Space.AudienceType.FRIENDS,
                false, true, "https://youtu.be/dQw4w9WgXcQ"));

            InOrder order = inOrder(content, txManager);
            order.verify(content).prepare("https://youtu.be/dQw4w9WgXcQ");
            order.verify(txManager).getTransaction(any());
            ArgumentCaptor<Space> space = ArgumentCaptor.forClass(Space.class);
            verify(spaces).save(space.capture());
            assertThat(space.getValue().getCurrentContent()).isSameAs(saved);
            assertThat(space.getValue().isAllowGuests()).isFalse();
            assertThat(space.getValue().isMembersCanAddContent()).isTrue();
        }

        @Test
        void unplayableFirstVideoStopsEverythingBeforeTheDatabase() {
            when(content.prepare("https://tiktok.com/x"))
                .thenThrow(ApiException.unprocessable("UNSUPPORTED_LINK", "nope"));

            assertApiError(() -> service.create(ada.getId(), new CreateSpaceRequest("Room", null,
                Space.AudienceType.FRIENDS, null, null, "https://tiktok.com/x")), HttpStatus.UNPROCESSABLE_CONTENT, "UNSUPPORTED_LINK");
            verifyNoInteractions(txManager, spaces, participants);
        }

        @Test
        void deletedAccountCantCreateRooms() {
            when(users.findById(ada.getId())).thenReturn(Optional.empty());
            assertApiError(() -> service.create(ada.getId(), new CreateSpaceRequest("Room", null,
                Space.AudienceType.OTHER, null, null, null)), HttpStatus.NOT_FOUND, "USER_NOT_FOUND");
        }
    }

    @Nested
    class Managing {

        private Space space;
        private Participant owner;
        private Participant admin;
        private Participant tunde;
        private Participant bisi;

        @BeforeEach
        void room() {
            space = space(ada);
            owner = member(space, ada, Participant.Role.OWNER);
            admin = member(space, user("Admin"), Participant.Role.ADMIN);
            tunde = member(space, user("Tunde"), Participant.Role.MEMBER);
            bisi = guest(space, "Bisi");
            for (Participant p : new Participant[] {owner, admin, tunde, bisi}) {
                lenient().when(access.require(principalOf(p), space.getId())).thenReturn(membership(p));
            }
        }

        @Test
        void viewingGoesThroughThePermissionGate() {
            service.get(principalOf(bisi), space.getId());

            verify(access).require(principalOf(bisi), space.getId());
            verify(views).of(argThat(m -> m.level() == Level.GUEST));
        }

        @Test
        void myRoomsComeStraightFromTheRepository() {
            java.util.List<com.mywatchmate.watch.dto.SpaceSummaryResponse> rooms = java.util.List.of();
            when(spaces.findRoomsOf(ada.getId(), true)).thenReturn(rooms);

            assertThat(service.listMine(ada.getId(), true)).isSameAs(rooms);
        }

        @Test
        void restoringAnActiveRoomChangesNothing() {
            service.restore(principalOf(owner), space.getId());

            assertThat(space.isArchived()).isFalse();
            verifyNoInteractions(events);
        }

        @Test
        void adminEditsOnlyTheFieldsSentAndEveryoneIsTold() {
            space.setDescription("Old");
            service.update(principalOf(admin), space.getId(),
                new UpdateSpaceRequest(" Morning Devotion ", null, null, false, null));

            assertThat(space.getTitle()).isEqualTo("Morning Devotion");
            assertThat(space.getDescription()).isEqualTo("Old");
            assertThat(space.isAllowGuests()).isFalse();
            assertThat(space.getAudienceType()).isEqualTo(Space.AudienceType.DIASPORA);
            verify(events).publish(eq(space.getId()), eq(Type.SPACE_UPDATED),
                argThat(data -> ((SpaceInfo) data).title().equals("Morning Devotion")));
        }

        @Test
        void emptyDescriptionClearsIt() {
            space.setDescription("Old");
            service.update(principalOf(owner), space.getId(), new UpdateSpaceRequest(null, "", null, null, null));
            assertThat(space.getDescription()).isNull();
        }

        @Test
        void membersCantEditTheRoom() {
            assertApiError(() -> service.update(principalOf(tunde), space.getId(),
                new UpdateSpaceRequest("Mine now", null, null, null, null)), HttpStatus.FORBIDDEN, "FORBIDDEN_ROLE");
            assertThat(space.getTitle()).isEqualTo("6am Prayer Room");
            verifyNoInteractions(events);
        }

        @Test
        void archivedRoomCantBeEdited() {
            space.archive(Instant.now());
            assertApiError(() -> service.update(principalOf(owner), space.getId(),
                new UpdateSpaceRequest("x", null, null, null, null)), HttpStatus.CONFLICT, "SPACE_ARCHIVED");
        }

        @Test
        void ownerArchivesOnceThenRestores() {
            service.archive(principalOf(owner), space.getId());
            Instant archivedAt = space.getArchivedAt();
            service.archive(principalOf(owner), space.getId());

            assertThat(space.getArchivedAt()).isEqualTo(archivedAt);
            verify(events).publish(eq(space.getId()), eq(Type.SPACE_ARCHIVED), any());

            service.restore(principalOf(owner), space.getId());
            assertThat(space.isArchived()).isFalse();
            verify(events).publish(eq(space.getId()), eq(Type.SPACE_RESTORED), any());
        }

        @Test
        void onlyTheOwnerArchives() {
            assertApiError(() -> service.archive(principalOf(admin), space.getId()), HttpStatus.FORBIDDEN, "FORBIDDEN_ROLE");
            assertThat(space.isArchived()).isFalse();
        }

        @Test
        void anyRegisteredMemberCanShareTheLinkButGuestsCant() {
            InviteResponse invite = new InviteResponse("invite-token-1", "http://localhost:3000/join/invite-token-1");
            when(inviteTokens.inviteFor(space)).thenReturn(invite);

            assertThat(service.invite(principalOf(tunde), space.getId())).isSameAs(invite);
            assertApiError(() -> service.invite(principalOf(bisi), space.getId()), HttpStatus.FORBIDDEN, "FORBIDDEN_ROLE");
        }

        @Test
        void rotatingReplacesTheTokenSoTheOldLinkDies() {
            when(inviteTokens.newToken()).thenReturn("brand-new-token");

            service.rotateInvite(principalOf(admin), space.getId());

            assertThat(space.getInviteToken()).isEqualTo("brand-new-token");
            verify(inviteTokens).inviteFor(space);
        }

        @Test
        void membersCantRotateTheLink() {
            assertThatThrownBy(() -> service.rotateInvite(principalOf(tunde), space.getId())).isInstanceOf(ApiException.class);
            assertThat(space.getInviteToken()).isEqualTo("invite-token-1");
            verify(inviteTokens, never()).newToken();
        }
    }
}
