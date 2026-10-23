package com.mywatchmate.sessions;

import static com.mywatchmate.testsupport.Fixtures.clockAt;
import static com.mywatchmate.testsupport.Fixtures.session;
import static com.mywatchmate.testsupport.Fixtures.space;
import static com.mywatchmate.testsupport.Fixtures.user;
import static com.mywatchmate.testsupport.Fixtures.weekdaySchedule;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mywatchmate.notify.ReminderService;
import com.mywatchmate.watch.ContentItem;
import com.mywatchmate.watch.Schedule;
import com.mywatchmate.watch.ScheduleRepository;
import com.mywatchmate.watch.Space;
import com.mywatchmate.watch.SpaceEvent;
import com.mywatchmate.watch.SpaceEvents;
import com.mywatchmate.watch.WatchSession;
import com.mywatchmate.watch.WatchSessionRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@ExtendWith(MockitoExtension.class)
class SessionJobsTest {

    private static final Instant SIX_AM_LAGOS = Instant.parse("2026-11-02T05:00:00Z");
    private static final SessionProperties PROPS = new SessionProperties(14, Duration.ofHours(3), Duration.ofMinutes(15));

    private final TransactionTemplate tx = new TransactionTemplate(mock(PlatformTransactionManager.class));
    private Space space;

    @BeforeEach
    void room() {
        space = space(user("Ada"));
    }

    @Nested
    class Lifecycle {

        @Mock WatchSessionRepository sessions;
        @Mock SessionService sessionService;
        @Mock ReminderService reminders;
        @Mock SessionViews views;
        @Mock SpaceEvents events;

        private SessionLifecycleJob jobAt(Instant now) {
            return new SessionLifecycleJob(sessions, sessionService, reminders, views, events, PROPS, tx, clockAt(now));
        }

        @Test
        void startsASessionWhenItsTimeComes() {
            WatchSession due = session(space, SIX_AM_LAGOS);
            when(sessions.lockDueToStart(SIX_AM_LAGOS, SessionLifecycleJob.BATCH)).thenReturn(List.of(due));

            assertThat(jobAt(SIX_AM_LAGOS).startDue()).isEqualTo(1);
            verify(sessionService).goLive(due);
        }

        @Test
        void waitsWhileAnotherSessionIsLiveInTheRoom() {
            WatchSession due = session(space, SIX_AM_LAGOS);
            Instant now = SIX_AM_LAGOS.plusSeconds(60);
            when(sessions.lockDueToStart(now, SessionLifecycleJob.BATCH)).thenReturn(List.of(due));
            when(sessions.existsBySpaceIdAndStatus(space.getId(), WatchSession.Status.LIVE)).thenReturn(true);

            assertThat(jobAt(now).startDue()).isZero();
            verify(sessionService, never()).goLive(any());
            assertThat(due.isScheduled()).isTrue();
        }

        @Test
        void archivedRoomsDontGoLive() {
            space.archive(SIX_AM_LAGOS);
            WatchSession due = session(space, SIX_AM_LAGOS);
            when(sessions.lockDueToStart(SIX_AM_LAGOS, SessionLifecycleJob.BATCH)).thenReturn(List.of(due));

            assertThat(jobAt(SIX_AM_LAGOS).startDue()).isZero();
            verify(sessionService, never()).goLive(any());
        }

        @Test
        void longOverdueSessionsAreMarkedMissed() {
            WatchSession stale = session(space, SIX_AM_LAGOS);
            Instant muchLater = SIX_AM_LAGOS.plus(Duration.ofHours(4));
            when(sessions.lockDueToStart(muchLater, SessionLifecycleJob.BATCH)).thenReturn(List.of(stale));

            assertThat(jobAt(muchLater).startDue()).isZero();
            assertThat(stale.getStatus()).isEqualTo(WatchSession.Status.ENDED);
            assertThat(stale.getStartedAt()).isNull();
            verify(reminders).cancelForSession(stale);
            verify(sessionService, never()).goLive(any());
        }

        @Test
        void endsSessionsWhoseVideoIsOverButNotOthers() {
            ContentItem tenMinutes = new ContentItem(space, ContentItem.Source.VIMEO, "https://vimeo.com/1");
            tenMinutes.setDurationSeconds(600);
            WatchSession shortOne = new WatchSession(space, tenMinutes, SIX_AM_LAGOS);
            shortOne.start(SIX_AM_LAGOS);
            WatchSession unknownLength = session(space, SIX_AM_LAGOS);
            unknownLength.start(SIX_AM_LAGOS);
            when(sessions.findByStatus(WatchSession.Status.LIVE)).thenReturn(List.of(shortOne, unknownLength));

            // 25 minutes in: past 10 min + 15 min grace, well short of 3 hours
            assertThat(jobAt(SIX_AM_LAGOS.plus(Duration.ofMinutes(25))).endFinished()).isEqualTo(1);

            assertThat(shortOne.getStatus()).isEqualTo(WatchSession.Status.ENDED);
            assertThat(unknownLength.isLive()).isTrue();
            verify(events).publish(eq(space.getId()), eq(SpaceEvent.Type.SESSION_ENDED), any());
        }

        @Test
        void unknownLengthEndsAfterThreeHours() {
            WatchSession live = session(space, SIX_AM_LAGOS);
            live.start(SIX_AM_LAGOS);
            when(sessions.findByStatus(WatchSession.Status.LIVE)).thenReturn(List.of(live));

            assertThat(jobAt(SIX_AM_LAGOS.plus(Duration.ofHours(3)).minusSeconds(1)).endFinished()).isZero();
            assertThat(jobAt(SIX_AM_LAGOS.plus(Duration.ofHours(3))).endFinished()).isEqualTo(1);
        }
    }

    @Nested
    class Generator {

        @Mock ScheduleRepository schedules;
        @Mock SessionPlanner planner;

        @Test
        void plansEveryActiveScheduleAndOneFailureDoesntStopTheRest() {
            Schedule broken = weekdaySchedule(space);
            Schedule raced = weekdaySchedule(space);
            Schedule fine = weekdaySchedule(space);
            when(schedules.findByActiveTrue()).thenReturn(List.of(broken, raced, fine));
            for (Schedule s : List.of(broken, raced, fine)) {
                when(schedules.findById(s.getId())).thenReturn(Optional.of(s));
            }
            when(planner.plan(broken)).thenThrow(new IllegalArgumentException("Unsupported recurrence rule"));
            when(planner.plan(raced)).thenThrow(new DataIntegrityViolationException("ux_watch_sessions_schedule_time"));
            when(planner.plan(fine)).thenReturn(3);

            assertThat(new SessionGeneratorJob(schedules, planner, tx).run()).isEqualTo(3);
            verify(planner).plan(fine);
        }
    }
}
