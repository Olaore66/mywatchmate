package com.mywatchmate.sessions;

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
import static com.mywatchmate.testsupport.Fixtures.video;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mywatchmate.notify.ReminderService;
import com.mywatchmate.sessions.dto.CreateSessionRequest;
import com.mywatchmate.sessions.dto.SessionResponse;
import com.mywatchmate.sessions.dto.WatchNowRequest;
import com.mywatchmate.watch.ContentItem;
import com.mywatchmate.watch.ContentItemRepository;
import com.mywatchmate.watch.Participant;
import com.mywatchmate.watch.Space;
import com.mywatchmate.watch.SpaceAccess;
import com.mywatchmate.watch.SpaceEvent;
import com.mywatchmate.watch.SpaceEvents;
import com.mywatchmate.watch.WatchSession;
import com.mywatchmate.watch.WatchSessionRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
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
class SessionServiceTest {

    @Mock WatchSessionRepository sessions;
    @Mock ContentItemRepository contentItems;
    @Mock SpaceAccess access;
    @Mock ReminderService reminders;
    @Mock SessionViews views;
    @Mock SpaceEvents events;

    private SessionService service;
    private Space space;
    private Participant owner;
    private Participant tunde;
    private Participant bisi;
    private final SessionResponse anyView = new SessionResponse(UUID.randomUUID(), null, null, null, null,
        WatchSession.Status.SCHEDULED, null, null, "UTC", null, null, null);

    @BeforeEach
    void setUp() {
        service = new SessionService(sessions, contentItems, access, reminders, views, events,
            new SessionProperties(14, Duration.ofHours(3), Duration.ofMinutes(15)), clockAt(MONDAY_MIDNIGHT));
        space = space(user("Ada"));
        owner = member(space, space.getOwner(), Participant.Role.OWNER);
        tunde = member(space, user("Tunde"), Participant.Role.MEMBER);
        bisi = guest(space, "Bisi");
        for (Participant p : List.of(owner, tunde, bisi)) {
            lenient().when(access.require(principalOf(p), space.getId())).thenReturn(membership(p));
        }
        lenient().when(views.zoneFor(any())).thenReturn(ZoneOffset.UTC);
        lenient().when(views.session(any(WatchSession.class))).thenReturn(anyView);
        lenient().when(views.session(any(WatchSession.class), any(ZoneId.class))).thenReturn(anyView);
        lenient().when(sessions.save(any(WatchSession.class))).thenAnswer(i -> i.getArgument(0));
    }

    @Nested
    class Listing {

        @Test
        void byDefaultLiveAndTheNextTwoWeeksInMyTimezone() {
            ZoneId lagos = ZoneId.of("Africa/Lagos");
            WatchSession upcoming = session(space, Instant.parse("2026-11-02T05:00:00Z"));
            when(views.zoneFor(principalOf(tunde))).thenReturn(lagos);
            when(sessions.findUpcoming(space.getId(), MONDAY_MIDNIGHT.plus(Duration.ofDays(14)),
                WatchSession.Status.LIVE, WatchSession.Status.SCHEDULED)).thenReturn(List.of(upcoming));

            assertThat(service.list(principalOf(tunde), space.getId(), null, null)).hasSize(1);
            verify(views).session(upcoming, lagos);
        }

        @Test
        void withAWindowItsHistory() {
            Instant from = Instant.parse("2026-10-01T00:00:00Z");
            Instant to = Instant.parse("2026-11-01T00:00:00Z");
            when(sessions.findBetween(space.getId(), from, to)).thenReturn(List.of());

            assertThat(service.list(principalOf(bisi), space.getId(), from, to)).isEmpty();
        }

        @Test
        void upcomingAcrossMyRoomsIsSevenDays() {
            when(sessions.findUpcomingForUser(tunde.getUser().getId(), MONDAY_MIDNIGHT, MONDAY_MIDNIGHT.plus(Duration.ofDays(7)),
                WatchSession.Status.LIVE, WatchSession.Status.SCHEDULED)).thenReturn(List.of(session(space, MONDAY_MIDNIGHT)));

            assertThat(service.upcomingForUser(principalOf(tunde))).hasSize(1);
        }
    }

    @Nested
    class OneOffAndWatchNow {

        @Test
        void hostSchedulesAOneOffWithRemindersAndEveryoneIsTold() {
            Instant at = Instant.parse("2026-11-08T17:00:00Z");
            service.createOneOff(principalOf(owner), space.getId(), new CreateSessionRequest(at, " Film night ", null));

            ArgumentCaptor<WatchSession> saved = ArgumentCaptor.forClass(WatchSession.class);
            verify(sessions).save(saved.capture());
            assertThat(saved.getValue().getScheduledAt()).isEqualTo(at);
            assertThat(saved.getValue().getTitle()).isEqualTo("Film night");
            assertThat(saved.getValue().getSchedule()).isNull();
            verify(reminders).scheduleFor(saved.getValue());
            verify(events).publish(space.getId(), SpaceEvent.Type.SESSION_SCHEDULED, anyView);
        }

        @Test
        void membersCantScheduleOneOffs() {
            assertApiError(() -> service.createOneOff(principalOf(tunde), space.getId(),
                new CreateSessionRequest(Instant.parse("2026-11-08T17:00:00Z"), null, null)), HttpStatus.FORBIDDEN, "FORBIDDEN_ROLE");
        }

        @Test
        void watchNowStartsALiveSessionWithTheChosenVideo() {
            ContentItem video = video(space);
            when(contentItems.findByIdAndSpaceId(video.getId(), space.getId())).thenReturn(Optional.of(video));

            service.watchNow(principalOf(owner), space.getId(), new WatchNowRequest("Catch-up", video.getId()));

            ArgumentCaptor<WatchSession> saved = ArgumentCaptor.forClass(WatchSession.class);
            verify(sessions).save(saved.capture());
            WatchSession session = saved.getValue();
            assertThat(session.isLive()).isTrue();
            assertThat(session.getStartedAt()).isEqualTo(MONDAY_MIDNIGHT);
            assertThat(session.getTitle()).isEqualTo("Catch-up");
            assertThat(space.getCurrentContent()).isSameAs(video);
            verify(events).publish(eq(space.getId()), eq(SpaceEvent.Type.NOW_PLAYING_CHANGED), any());
            verify(events).publish(space.getId(), SpaceEvent.Type.SESSION_STARTED, anyView);
        }

        @Test
        void watchNowWithoutAVideoKeepsWhatsPlaying() {
            ContentItem playing = video(space);
            space.playNow(playing);

            service.watchNow(principalOf(owner), space.getId(), null);

            ArgumentCaptor<WatchSession> saved = ArgumentCaptor.forClass(WatchSession.class);
            verify(sessions).save(saved.capture());
            assertThat(saved.getValue().getContentItem()).isSameAs(playing);
            verify(events, never()).publish(eq(space.getId()), eq(SpaceEvent.Type.NOW_PLAYING_CHANGED), any());
        }

        @Test
        void watchNowFollowsTheAddVideosRule() {
            assertApiError(() -> service.watchNow(principalOf(tunde), space.getId(), null), HttpStatus.FORBIDDEN, "FORBIDDEN_ROLE");
            assertApiError(() -> service.watchNow(principalOf(bisi), space.getId(), null), HttpStatus.FORBIDDEN, "FORBIDDEN_ROLE");

            space.setMembersCanAddContent(true);
            service.watchNow(principalOf(tunde), space.getId(), null);
            verify(sessions).save(any(WatchSession.class));
        }

        @Test
        void onlyOneLiveSessionPerRoom() {
            when(sessions.existsBySpaceIdAndStatus(space.getId(), WatchSession.Status.LIVE)).thenReturn(true);

            assertApiError(() -> service.watchNow(principalOf(owner), space.getId(), null), HttpStatus.CONFLICT, "SESSION_ALREADY_LIVE");
            verify(sessions, never()).save(any());
        }
    }

    @Nested
    class ManualControl {

        private WatchSession scheduled;

        @BeforeEach
        void session() {
            scheduled = com.mywatchmate.testsupport.Fixtures.session(space, Instant.parse("2026-11-02T05:00:00Z"));
            lenient().when(sessions.findByIdAndSpaceId(scheduled.getId(), space.getId())).thenReturn(Optional.of(scheduled));
        }

        @Test
        void hostStartsEarlyThenEnds() {
            service.start(principalOf(owner), space.getId(), scheduled.getId());
            assertThat(scheduled.isLive()).isTrue();
            verify(events).publish(space.getId(), SpaceEvent.Type.SESSION_STARTED, anyView);

            service.end(principalOf(owner), space.getId(), scheduled.getId());
            assertThat(scheduled.getStatus()).isEqualTo(WatchSession.Status.ENDED);
            verify(events).publish(space.getId(), SpaceEvent.Type.SESSION_ENDED, anyView);
        }

        @Test
        void startingWhileAnotherIsLiveIsAConflict() {
            when(sessions.existsBySpaceIdAndStatus(space.getId(), WatchSession.Status.LIVE)).thenReturn(true);

            assertApiError(() -> service.start(principalOf(owner), space.getId(), scheduled.getId()),
                HttpStatus.CONFLICT, "SESSION_ALREADY_LIVE");
            assertThat(scheduled.isScheduled()).isTrue();
        }

        @Test
        void cancellingCancelsItsReminders() {
            service.cancel(principalOf(owner), space.getId(), scheduled.getId());

            assertThat(scheduled.getStatus()).isEqualTo(WatchSession.Status.CANCELLED);
            verify(reminders).cancelForSession(scheduled);
            verify(events).publish(space.getId(), SpaceEvent.Type.SESSION_CANCELLED, anyView);
        }

        @Test
        void membersCantControlSessions() {
            assertApiError(() -> service.start(principalOf(tunde), space.getId(), scheduled.getId()), HttpStatus.FORBIDDEN, "FORBIDDEN_ROLE");
            assertApiError(() -> service.cancel(principalOf(tunde), space.getId(), scheduled.getId()), HttpStatus.FORBIDDEN, "FORBIDDEN_ROLE");
            assertThat(scheduled.isScheduled()).isTrue();
        }

        @Test
        void unknownSessionIs404() {
            UUID missing = UUID.randomUUID();
            when(sessions.findByIdAndSpaceId(missing, space.getId())).thenReturn(Optional.empty());

            assertApiError(() -> service.end(principalOf(owner), space.getId(), missing), HttpStatus.NOT_FOUND, "SESSION_NOT_FOUND");
        }

        @Test
        void endingSomethingNotLiveIsAConflict() {
            assertApiError(() -> service.end(principalOf(owner), space.getId(), scheduled.getId()),
                HttpStatus.CONFLICT, "SESSION_WRONG_STATUS");
        }
    }
}
