package com.mywatchmate.live;

import static com.mywatchmate.testsupport.Fixtures.MONDAY_MIDNIGHT;
import static com.mywatchmate.testsupport.Fixtures.assertApiError;
import static com.mywatchmate.testsupport.Fixtures.clockAt;
import static com.mywatchmate.testsupport.Fixtures.guest;
import static com.mywatchmate.testsupport.Fixtures.member;
import static com.mywatchmate.testsupport.Fixtures.membership;
import static com.mywatchmate.testsupport.Fixtures.principalOf;
import static com.mywatchmate.testsupport.Fixtures.space;
import static com.mywatchmate.testsupport.Fixtures.user;
import static com.mywatchmate.testsupport.Fixtures.video;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.mywatchmate.common.ApiException;
import com.mywatchmate.live.PlaybackState.Status;
import com.mywatchmate.live.dto.PlaybackCommand;
import com.mywatchmate.live.dto.PlaybackCommand.Action;
import com.mywatchmate.live.dto.PlaybackResponse;
import com.mywatchmate.watch.ContentItem;
import com.mywatchmate.watch.ContentItemRepository;
import com.mywatchmate.watch.Participant;
import com.mywatchmate.watch.Space;
import com.mywatchmate.watch.SpaceAccess;
import com.mywatchmate.watch.SpaceEvent;
import com.mywatchmate.watch.SpaceEvents;
import com.mywatchmate.watch.dto.ContentResponse;
import java.time.Instant;
import java.util.Optional;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

@ExtendWith(MockitoExtension.class)
class PlaybackServiceTest {

    private static final Instant NOW = MONDAY_MIDNIGHT;

    @Mock PlaybackStateRepository states;
    @Mock ContentItemRepository contentItems;
    @Mock SpaceAccess access;
    @Mock SpaceEvents events;
    @Mock CommandRateLimiter rateLimiter;

    private PlaybackService service;
    private Space space;
    private Participant owner;
    private Participant tunde;
    private ContentItem tenMinutes;
    private PlaybackState state;

    @BeforeEach
    void setUp() {
        service = new PlaybackService(states, contentItems, access, events, rateLimiter, clockAt(NOW));
        space = space(user("Ada"));
        owner = member(space, space.getOwner(), Participant.Role.OWNER);
        tunde = member(space, user("Tunde"), Participant.Role.MEMBER);
        tenMinutes = video(space);
        tenMinutes.setDurationSeconds(600);
        space.playNow(tenMinutes);
        state = PlaybackState.initial(space.getId(), null, NOW.minusSeconds(60));
        state.load(tenMinutes, NOW.minusSeconds(60));
        lenient().when(states.findForUpdate(space.getId())).thenReturn(Optional.of(state));
    }

    private void callerIs(Participant participant) {
        when(access.require(principalOf(participant), space.getId())).thenReturn(membership(participant));
    }

    private PlaybackResponse press(Participant who, Action action, Long positionMs) {
        return service.command(principalOf(who), space.getId(), new PlaybackCommand(action, positionMs));
    }

    @Nested
    class Reading {

        @Test
        void theSavedStateAsOfNow() {
            callerIs(tunde);
            when(states.findWithContent(space.getId())).thenReturn(Optional.of(state));

            PlaybackResponse response = service.get(principalOf(tunde), space.getId());

            assertThat(response.status()).isEqualTo(Status.PLAYING);
            assertThat(response.positionMs()).as("a minute in").isEqualTo(60_000);
            assertThat(response.serverTimeMs()).isEqualTo(NOW.toEpochMilli());
            assertThat(response.contentId()).isEqualTo(tenMinutes.getId());
            assertThat(response.durationMs()).isEqualTo(600_000);
            assertThat(response.version()).isEqualTo(1);
        }

        @Test
        void aRoomNobodyHasPressedAnythingInIsPausedAtTheStart() {
            when(states.findWithContent(space.getId())).thenReturn(Optional.empty());

            PlaybackResponse response = service.current(space);

            assertThat(response.status()).isEqualTo(Status.PAUSED);
            assertThat(response.positionMs()).isZero();
            assertThat(response.contentId()).isEqualTo(tenMinutes.getId());
            assertThat(response.version()).isZero();
        }
    }

    @Nested
    class Pressing {

        @Test
        void ownerPausesForEveryone() {
            callerIs(owner);

            PlaybackResponse response = press(owner, Action.PAUSE, null);

            assertThat(response.status()).isEqualTo(Status.PAUSED);
            assertThat(response.positionMs()).isEqualTo(60_000);
            assertThat(response.version()).isEqualTo(2);
            assertThat(response.updatedBy().displayName()).isEqualTo("Ada");
            verify(events).publish(space.getId(), SpaceEvent.Type.PLAYBACK_CHANGED, response);
        }

        @Test
        void theRowIsCreatedIfNeededThenLockedBeforeChanging() {
            callerIs(owner);

            press(owner, Action.PLAY, null);

            InOrder order = inOrder(rateLimiter, states);
            order.verify(rateLimiter).check(owner.getId());
            order.verify(states).flush();
            order.verify(states).insertIfMissing(space.getId());
            order.verify(states).findForUpdate(space.getId());
        }

        @Test
        void seekJumpsAndKeepsPlaying() {
            callerIs(owner);

            PlaybackResponse response = press(owner, Action.SEEK, 300_000L);

            assertThat(response.positionMs()).isEqualTo(300_000);
            assertThat(response.status()).isEqualTo(Status.PLAYING);
        }

        @Test
        void seekingToTheVeryEndIsAllowedButNotPastIt() {
            callerIs(owner);
            assertThat(press(owner, Action.SEEK, 600_000L).positionMs()).isEqualTo(600_000);

            assertApiError(() -> press(owner, Action.SEEK, 600_001L), HttpStatus.UNPROCESSABLE_CONTENT, "POSITION_OUT_OF_RANGE");
        }

        @Test
        void seekNeedsAPosition() {
            callerIs(owner);
            assertApiError(() -> press(owner, Action.SEEK, null), HttpStatus.BAD_REQUEST, "POSITION_REQUIRED");
            verify(events, never()).publish(any(), any(), any());
        }

        @Test
        void liveStreamsCantBeSeekedButCanBePaused() {
            tenMinutes.setLive(true);
            callerIs(owner);

            assertApiError(() -> press(owner, Action.SEEK, 1_000L), HttpStatus.UNPROCESSABLE_CONTENT, "LIVE_CANNOT_SEEK");
            assertThat(press(owner, Action.PAUSE, null).status()).isEqualTo(Status.PAUSED);
        }

        @Test
        void unknownLengthSeeksAnywhere() {
            tenMinutes.setDurationSeconds(null);
            callerIs(owner);

            assertThat(press(owner, Action.SEEK, 9_000_000L).positionMs()).isEqualTo(9_000_000);
        }

        @Test
        void nothingPlayingIs409() {
            state.load(null, NOW);
            callerIs(owner);

            assertApiError(() -> press(owner, Action.PLAY, null), HttpStatus.CONFLICT, "NOTHING_PLAYING");
        }

        @Test
        void tooManyPressesAreRefusedBeforeAnythingChanges() {
            callerIs(owner);
            doThrow(new ApiException(HttpStatus.TOO_MANY_REQUESTS, "PLAYBACK_RATE_LIMITED", "slow down"))
                .when(rateLimiter).check(owner.getId());

            assertApiError(() -> press(owner, Action.PAUSE, null), HttpStatus.TOO_MANY_REQUESTS, "PLAYBACK_RATE_LIMITED");
            assertThat(state.getStatus()).isEqualTo(Status.PLAYING);
            verifyNoInteractions(states, events);
        }
    }

    @Nested
    class WhoCanPress {

        @Test
        void membersOnlyWhenTheRoomLetsThem() {
            callerIs(tunde);
            assertApiError(() -> press(tunde, Action.PAUSE, null), HttpStatus.FORBIDDEN, "FORBIDDEN_ROLE");
            verifyNoInteractions(rateLimiter, states, events);

            space.setMembersCanControlPlayback(true);
            assertThat(press(tunde, Action.PAUSE, null).updatedBy().displayName()).isEqualTo("Tunde");
        }

        @Test
        void guestsNever() {
            space.setMembersCanControlPlayback(true);
            Participant bisi = guest(space, "Bisi");
            callerIs(bisi);

            assertForbidden(() -> press(bisi, Action.PLAY, null), "Guests can't control playback. Create an account to continue.");
        }

        @Test
        void hostLedRoomsSayWhoCan() {
            callerIs(tunde);
            assertForbidden(() -> press(tunde, Action.PLAY, null),
                "Only the owner or admins can control playback in this room.");
        }

        @Test
        void archivedRoomsAreFrozen() {
            space.archive(NOW);
            callerIs(owner);
            assertApiError(() -> press(owner, Action.PLAY, null), HttpStatus.CONFLICT, "SPACE_ARCHIVED");
        }

        private void assertForbidden(ThrowingCallable call, String message) {
            assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class, e -> {
                assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                assertThat(e.getCode()).isEqualTo("FORBIDDEN_ROLE");
                assertThat(e).hasMessage(message);
            });
        }
    }

    @Nested
    class NewVideo {

        @Test
        void startsFromTheBeginningForEveryone() {
            ContentItem next = video(space);
            when(contentItems.findById(next.getId())).thenReturn(Optional.of(next));

            service.onSpaceEvent(new SpaceEvent(SpaceEvent.Type.NOW_PLAYING_CHANGED, space.getId(), NOW,
                ContentResponse.from(next)));

            assertThat(state.getContent()).isSameAs(next);
            assertThat(state.getStatus()).isEqualTo(Status.PLAYING);
            assertThat(state.positionAt(NOW)).isZero();
            ArgumentCaptor<Object> sent = ArgumentCaptor.forClass(Object.class);
            verify(events).publish(eq(space.getId()), eq(SpaceEvent.Type.PLAYBACK_CHANGED), sent.capture());
            assertThat(((PlaybackResponse) sent.getValue()).contentId()).isEqualTo(next.getId());
        }

        @Test
        void stoppingMeansNothingToPlay() {
            service.onSpaceEvent(new SpaceEvent(SpaceEvent.Type.NOW_PLAYING_CHANGED, space.getId(), NOW, null));

            assertThat(state.getStatus()).isEqualTo(Status.STOPPED);
            assertThat(state.getContent()).isNull();
            verify(events).publish(eq(space.getId()), eq(SpaceEvent.Type.PLAYBACK_CHANGED), any(PlaybackResponse.class));
        }

        @Test
        void otherEventsAreIgnored() {
            service.onSpaceEvent(new SpaceEvent(SpaceEvent.Type.CONTENT_ADDED, space.getId(), NOW, null));
            verifyNoInteractions(states, events, contentItems);
        }
    }
}
