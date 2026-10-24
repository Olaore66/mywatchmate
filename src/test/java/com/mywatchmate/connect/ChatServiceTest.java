package com.mywatchmate.connect;

import static com.mywatchmate.testsupport.Fixtures.MONDAY_MIDNIGHT;
import static com.mywatchmate.testsupport.Fixtures.assertApiError;
import static com.mywatchmate.testsupport.Fixtures.clockAt;
import static com.mywatchmate.testsupport.Fixtures.guest;
import static com.mywatchmate.testsupport.Fixtures.member;
import static com.mywatchmate.testsupport.Fixtures.membership;
import static com.mywatchmate.testsupport.Fixtures.principalOf;
import static com.mywatchmate.testsupport.Fixtures.session;
import static com.mywatchmate.testsupport.Fixtures.space;
import static com.mywatchmate.testsupport.Fixtures.user;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.mywatchmate.common.SlidingWindowLimiter;
import com.mywatchmate.connect.dto.ChatMessageResponse;
import com.mywatchmate.connect.dto.ChatPage;
import com.mywatchmate.connect.dto.MemberMuted;
import com.mywatchmate.watch.Participant;
import com.mywatchmate.watch.ParticipantRepository;
import com.mywatchmate.watch.Space;
import com.mywatchmate.watch.SpaceAccess;
import com.mywatchmate.watch.SpaceEvent;
import com.mywatchmate.watch.SpaceEvents;
import com.mywatchmate.watch.WatchSession;
import com.mywatchmate.watch.WatchSessionRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

@ExtendWith(MockitoExtension.class)
class ChatServiceTest {

    private static final Instant NOW = MONDAY_MIDNIGHT;

    @Mock ChatMessageRepository messages;
    @Mock ParticipantRepository participants;
    @Mock WatchSessionRepository sessions;
    @Mock SpaceAccess access;
    @Mock SpaceEvents events;
    @Mock SlidingWindowLimiter limiter;

    private ChatService service;
    private Space space;
    private Participant ada;
    private Participant femi;
    private Participant tunde;
    private Participant bisi;

    @BeforeEach
    void setUp() {
        service = new ChatService(messages, participants, sessions, access, events, clockAt(NOW), limiter);
        space = space(user("Ada"));
        ada = member(space, space.getOwner(), Participant.Role.OWNER);
        femi = member(space, user("Femi"), Participant.Role.ADMIN);
        tunde = member(space, user("Tunde"), Participant.Role.MEMBER);
        bisi = guest(space, "Bisi");
        for (Participant p : List.of(ada, femi, tunde, bisi)) {
            lenient().when(access.require(principalOf(p), space.getId())).thenReturn(membership(p));
            lenient().when(participants.findById(p.getId())).thenReturn(Optional.of(p));
        }
        lenient().when(limiter.tryAcquire(any())).thenReturn(true);
        lenient().when(messages.saveAndFlush(any(ChatMessage.class))).thenAnswer(i -> i.getArgument(0));
        lenient().when(sessions.findFirstBySpaceIdAndStatus(space.getId(), WatchSession.Status.LIVE))
            .thenReturn(Optional.empty());
    }

    private ChatMessage message(Participant from, String body, Instant at) {
        ChatMessage m = new ChatMessage(space, null, from, body, at);
        lenient().when(messages.findInSpace(space.getId(), m.getId())).thenReturn(Optional.of(m));
        return m;
    }

    @Nested
    class Sending {

        @Test
        void savedTrimmedAndToldToTheRoom() {
            ChatMessageResponse sent = service.send(principalOf(bisi), space.getId(), "  Amen 🙏  ");

            assertThat(sent.body()).isEqualTo("Amen 🙏");
            assertThat(sent.sender().displayName()).isEqualTo("Bisi");
            assertThat(sent.senderGuest()).isTrue();
            assertThat(sent.sessionId()).as("nothing live").isNull();
            assertThat(sent.createdAt()).isEqualTo(NOW);
            verify(events).publish(space.getId(), SpaceEvent.Type.CHAT_MESSAGE, sent);
        }

        @Test
        void duringALiveSessionTheMessageIsTaggedWithIt() {
            WatchSession live = session(space, NOW);
            live.start(NOW);
            when(sessions.findFirstBySpaceIdAndStatus(space.getId(), WatchSession.Status.LIVE)).thenReturn(Optional.of(live));

            assertThat(service.send(principalOf(tunde), space.getId(), "Good word").sessionId()).isEqualTo(live.getId());
        }

        @Test
        void yourOwnMessagesAreNeverUnread() {
            service.send(principalOf(tunde), space.getId(), "Hi");

            assertThat(tunde.getChatLastReadAt()).isEqualTo(NOW);
        }

        @Test
        void mutedPeopleCantSendAndAreToldUntilWhen() {
            tunde.muteChat(NOW.plus(Duration.ofHours(1)));

            assertApiError(() -> service.send(principalOf(tunde), space.getId(), "Hi"), HttpStatus.FORBIDDEN, "CHAT_MUTED");
            assertThatThrownBy(() -> service.send(principalOf(tunde), space.getId(), "Hi"))
                .hasMessage("A host has muted you in this chat until 2026-11-02T01:00:00Z.");
            verify(messages, never()).saveAndFlush(any());
        }

        @Test
        void mutedUntilUnmutedSaysSo() {
            tunde.muteChat(ChatService.MUTED_INDEFINITELY);

            assertThatThrownBy(() -> service.send(principalOf(tunde), space.getId(), "Hi"))
                .hasMessage("A host has muted you in this chat.");
        }

        @Test
        void anExpiredMuteNoLongerApplies() {
            tunde.muteChat(NOW.minusSeconds(1));

            assertThat(service.send(principalOf(tunde), space.getId(), "Back").body()).isEqualTo("Back");
        }

        @Test
        void tooFastIs429AndNothingIsSaved() {
            when(limiter.tryAcquire(tunde.getId())).thenReturn(false);

            assertApiError(() -> service.send(principalOf(tunde), space.getId(), "spam"),
                HttpStatus.TOO_MANY_REQUESTS, "CHAT_RATE_LIMITED");
            verify(messages, never()).saveAndFlush(any());
            verifyNoInteractions(events);
        }

        @Test
        void archivedRoomsAreReadOnly() {
            space.archive(NOW);
            assertApiError(() -> service.send(principalOf(ada), space.getId(), "Hi"), HttpStatus.CONFLICT, "SPACE_ARCHIVED");
        }
    }

    @Nested
    class History {

        @Test
        void theNewestPageOldestFirstWithACursorWhenThereIsMore() {
            ChatMessage older = message(ada, "one", NOW);
            ChatMessage newer = message(bisi, "two", NOW.plusSeconds(1));
            when(messages.findLatestIds(space.getId(), 3))
                .thenReturn(List.of(newer.getId().toString(), older.getId().toString(), UUID.randomUUID().toString()));
            when(messages.findWithSenders(space.getId(), List.of(newer.getId(), older.getId())))
                .thenReturn(List.of(older, newer));

            ChatPage page = service.history(principalOf(tunde), space.getId(), null, 2);

            assertThat(page.messages()).extracting(ChatMessageResponse::body).containsExactly("one", "two");
            assertThat(ChatCursor.decode(page.olderCursor())).isEqualTo(ChatCursor.of(older));
        }

        @Test
        void theStartOfTheChatHasNoCursor() {
            ChatMessage only = message(ada, "first ever", NOW);
            when(messages.findLatestIds(space.getId(), ChatService.DEFAULT_PAGE + 1))
                .thenReturn(List.of(only.getId().toString()));
            when(messages.findWithSenders(space.getId(), List.of(only.getId()))).thenReturn(List.of(only));

            ChatPage page = service.history(principalOf(tunde), space.getId(), null, null);

            assertThat(page.messages()).hasSize(1);
            assertThat(page.olderCursor()).isNull();
        }

        @Test
        void anEmptyChat() {
            when(messages.findLatestIds(space.getId(), ChatService.DEFAULT_PAGE + 1)).thenReturn(List.of());

            assertThat(service.history(principalOf(tunde), space.getId(), null, null)).isEqualTo(new ChatPage(List.of(), null));
            verify(messages, never()).findWithSenders(any(), any());
        }

        @Test
        void thePageAboveTheCursor() {
            ChatCursor cursor = new ChatCursor(NOW, UUID.randomUUID());
            when(messages.findIdsBefore(space.getId(), NOW, cursor.id(), ChatService.DEFAULT_PAGE + 1)).thenReturn(List.of());

            assertThat(service.history(principalOf(tunde), space.getId(), cursor.encode(), null).messages()).isEmpty();
        }

        @Test
        void pageSizesAreKeptSensible() {
            when(messages.findLatestIds(eq(space.getId()), anyInt())).thenReturn(List.of());

            service.history(principalOf(tunde), space.getId(), null, 0);
            service.history(principalOf(tunde), space.getId(), null, 5_000);

            verify(messages).findLatestIds(space.getId(), 2);
            verify(messages).findLatestIds(space.getId(), ChatService.MAX_PAGE + 1);
        }

        @Test
        void deletedMessagesStayAsPlaceholders() {
            ChatMessage gone = message(bisi, "oops", NOW);
            gone.delete(NOW, ada);
            when(messages.findLatestIds(space.getId(), ChatService.DEFAULT_PAGE + 1)).thenReturn(List.of(gone.getId().toString()));
            when(messages.findWithSenders(space.getId(), List.of(gone.getId()))).thenReturn(List.of(gone));

            ChatMessageResponse shown = service.history(principalOf(tunde), space.getId(), null, null).messages().getFirst();

            assertThat(shown.deleted()).isTrue();
            assertThat(shown.body()).isNull();
            assertThat(shown.deletedByHost()).isTrue();
        }

        @Test
        void aMadeUpCursorIs400() {
            assertApiError(() -> service.history(principalOf(tunde), space.getId(), "garbage!", null),
                HttpStatus.BAD_REQUEST, "INVALID_CURSOR");
        }
    }

    @Nested
    class Deleting {

        @Test
        void sendersDeleteTheirOwnAndTheRoomIsTold() {
            ChatMessage mine = message(bisi, "oops", NOW);

            service.delete(principalOf(bisi), space.getId(), mine.getId());

            assertThat(mine.isDeleted()).isTrue();
            assertThat(mine.getDeletedBy()).isSameAs(bisi);
            verify(events).publish(space.getId(), SpaceEvent.Type.CHAT_DELETED, Map.of("messageId", mine.getId()));
        }

        @Test
        void hostsDeleteAnyoneElsesMembersDont() {
            ChatMessage rude = message(bisi, "rude", NOW);

            assertApiError(() -> service.delete(principalOf(tunde), space.getId(), rude.getId()),
                HttpStatus.FORBIDDEN, "NOT_YOUR_MESSAGE");
            service.delete(principalOf(femi), space.getId(), rude.getId());

            assertThat(rude.getDeletedBy()).isSameAs(femi);
        }

        @Test
        void deletingTwiceIsHarmlessAndAnnouncedOnce() {
            ChatMessage mine = message(bisi, "oops", NOW);

            service.delete(principalOf(bisi), space.getId(), mine.getId());
            service.delete(principalOf(bisi), space.getId(), mine.getId());

            verify(events).publish(eq(space.getId()), eq(SpaceEvent.Type.CHAT_DELETED), any());
        }

        @Test
        void aMessageFromAnotherRoomIsNotFound() {
            UUID elsewhere = UUID.randomUUID();
            when(messages.findInSpace(space.getId(), elsewhere)).thenReturn(Optional.empty());

            assertApiError(() -> service.delete(principalOf(ada), space.getId(), elsewhere),
                HttpStatus.NOT_FOUND, "MESSAGE_NOT_FOUND");
        }
    }

    @Nested
    class Reading {

        @Test
        void markingReadMovesTheReadPointToNow() {
            service.markRead(principalOf(tunde), space.getId());

            assertThat(tunde.getChatLastReadAt()).isEqualTo(NOW);
        }

        @Test
        void unreadCountsFromTheReadPoint() {
            tunde.setJoinedAt(NOW.minusSeconds(3600));
            when(messages.countUnread(space.getId(), tunde.getId(), NOW.minusSeconds(3600))).thenReturn(7L);

            assertThat(service.unreadCount(tunde)).isEqualTo(7);
        }
    }

    @Nested
    class Muting {

        @Test
        void hostsMuteForAWhileAndEveryoneIsTold() {
            MemberMuted muted = service.mute(principalOf(femi), space.getId(), tunde.getId(), 60);

            assertThat(tunde.getChatMutedUntil()).isEqualTo(NOW.plus(Duration.ofMinutes(60)));
            assertThat(muted.mutedUntil()).isEqualTo(NOW.plus(Duration.ofMinutes(60)));
            assertThat(muted.participant().displayName()).isEqualTo("Tunde");
            verify(events).publish(space.getId(), SpaceEvent.Type.MEMBER_MUTED, muted);
        }

        @Test
        void withoutMinutesUntilUnmuted() {
            MemberMuted muted = service.mute(principalOf(ada), space.getId(), bisi.getId(), null);

            assertThat(bisi.getChatMutedUntil()).isEqualTo(ChatService.MUTED_INDEFINITELY);
            assertThat(muted.mutedUntil()).as("shown as null").isNull();
            assertThat(service.isMuted(bisi.getId())).isTrue();
        }

        @Test
        void unmutingLiftsItAndIsAnnouncedOnlyIfItWasOn() {
            tunde.muteChat(NOW.plusSeconds(600));

            service.unmute(principalOf(ada), space.getId(), tunde.getId());
            service.unmute(principalOf(ada), space.getId(), tunde.getId());

            assertThat(tunde.isChatMuted(NOW)).isFalse();
            verify(events).publish(space.getId(), SpaceEvent.Type.MEMBER_UNMUTED, Map.of("participantId", tunde.getId()));
        }

        @Test
        void theOwnerCantBeMutedAndAdminsCantMuteAdmins() {
            Participant otherAdmin = member(space, user("Kemi"), Participant.Role.ADMIN);
            when(participants.findById(otherAdmin.getId())).thenReturn(Optional.of(otherAdmin));

            assertApiError(() -> service.mute(principalOf(femi), space.getId(), ada.getId(), 60),
                HttpStatus.FORBIDDEN, "FORBIDDEN_ROLE");
            assertApiError(() -> service.mute(principalOf(femi), space.getId(), otherAdmin.getId(), 60),
                HttpStatus.FORBIDDEN, "FORBIDDEN_ROLE");
            assertThat(service.mute(principalOf(ada), space.getId(), femi.getId(), 60).participant().id())
                .as("the owner can mute an admin").isEqualTo(femi.getId());
        }

        @Test
        void membersCantMuteAndNobodyMutesThemselves() {
            assertApiError(() -> service.mute(principalOf(tunde), space.getId(), bisi.getId(), 60),
                HttpStatus.FORBIDDEN, "FORBIDDEN_ROLE");
            assertApiError(() -> service.mute(principalOf(femi), space.getId(), femi.getId(), 60),
                HttpStatus.BAD_REQUEST, "CANNOT_TARGET_SELF");
        }

        @Test
        void peopleWhoLeftOrFromElsewhereAreNotFound() {
            tunde.leave(NOW);
            Participant stranger = member(space(user("Kemi")), user("Kemi"), Participant.Role.MEMBER);
            when(participants.findById(stranger.getId())).thenReturn(Optional.of(stranger));

            assertApiError(() -> service.mute(principalOf(ada), space.getId(), tunde.getId(), 60),
                HttpStatus.NOT_FOUND, "PARTICIPANT_NOT_FOUND");
            assertApiError(() -> service.mute(principalOf(ada), space.getId(), stranger.getId(), 60),
                HttpStatus.NOT_FOUND, "PARTICIPANT_NOT_FOUND");
        }

        @Test
        void unknownPeopleCountAsMutedForTheRelay() {
            UUID nobody = UUID.randomUUID();
            when(participants.findById(nobody)).thenReturn(Optional.empty());

            assertThat(service.isMuted(nobody)).isTrue();
            assertThat(service.isMuted(tunde.getId())).isFalse();
        }

        @Test
        void mutingSendsNothingIfRefused() {
            assertApiError(() -> service.mute(principalOf(tunde), space.getId(), bisi.getId(), 60),
                HttpStatus.FORBIDDEN, "FORBIDDEN_ROLE");
            verifyNoInteractions(events);
        }
    }
}
