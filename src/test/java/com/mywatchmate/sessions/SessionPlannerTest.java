package com.mywatchmate.sessions;

import static com.mywatchmate.testsupport.Fixtures.MONDAY_MIDNIGHT;
import static com.mywatchmate.testsupport.Fixtures.clockAt;
import static com.mywatchmate.testsupport.Fixtures.space;
import static com.mywatchmate.testsupport.Fixtures.user;
import static com.mywatchmate.testsupport.Fixtures.weekdaySchedule;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.mywatchmate.notify.ReminderService;
import com.mywatchmate.watch.Schedule;
import com.mywatchmate.watch.Space;
import com.mywatchmate.watch.WatchSession;
import com.mywatchmate.watch.WatchSessionRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SessionPlannerTest {

    @Mock WatchSessionRepository sessions;
    @Mock ReminderService reminders;

    private SessionPlanner planner;
    private Space space;
    private Schedule schedule;

    @BeforeEach
    void setUp() {
        planner = new SessionPlanner(sessions, reminders,
            new SessionProperties(14, Duration.ofHours(3), Duration.ofMinutes(15)), clockAt(MONDAY_MIDNIGHT));
        space = space(user("Ada"));
        schedule = weekdaySchedule(space);
        lenient().when(sessions.save(any(WatchSession.class))).thenAnswer(i -> i.getArgument(0));
    }

    @Test
    void plansTwoWeeksOfWeekdaysWithReminders() {
        int created = planner.plan(schedule);

        assertThat(created).isEqualTo(10);
        ArgumentCaptor<WatchSession> saved = ArgumentCaptor.forClass(WatchSession.class);
        verify(sessions, times(10)).save(saved.capture());
        List<WatchSession> planned = saved.getAllValues();
        assertThat(planned.get(0).getScheduledAt()).isEqualTo(Instant.parse("2026-11-02T05:00:00Z"));
        assertThat(planned.get(9).getScheduledAt()).isEqualTo(Instant.parse("2026-11-13T05:00:00Z"));
        assertThat(planned).allSatisfy(s -> {
            assertThat(s.getSchedule()).isSameAs(schedule);
            assertThat(s.getTitle()).isEqualTo("Morning Devotion");
            assertThat(s.isScheduled()).isTrue();
        });
        verify(reminders, times(10)).scheduleFor(any(WatchSession.class));
    }

    @Test
    void runningAgainCreatesNothingNew() {
        when(sessions.existsByScheduleIdAndScheduledAt(eq(schedule.getId()), any(Instant.class))).thenReturn(true);

        assertThat(planner.plan(schedule)).isZero();
        verify(sessions, never()).save(any());
        verifyNoInteractions(reminders);
    }

    @Test
    void onlyTheMissingSittingsAreAdded() {
        when(sessions.existsByScheduleIdAndScheduledAt(eq(schedule.getId()), any(Instant.class)))
            .thenAnswer(i -> i.<Instant>getArgument(1).isBefore(Instant.parse("2026-11-13T00:00:00Z")));

        assertThat(planner.plan(schedule)).isEqualTo(1);
    }

    @Test
    void stoppedSchedulesAndArchivedRoomsPlanNothing() {
        schedule.setActive(false);
        assertThat(planner.plan(schedule)).isZero();

        schedule.setActive(true);
        space.archive(MONDAY_MIDNIGHT);
        assertThat(planner.plan(schedule)).isZero();
        verifyNoInteractions(sessions, reminders);
    }
}
