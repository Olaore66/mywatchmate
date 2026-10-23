package com.mywatchmate.sessions;

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
import static com.mywatchmate.testsupport.Fixtures.weekdaySchedule;
import static java.time.DayOfWeek.FRIDAY;
import static java.time.DayOfWeek.MONDAY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.mywatchmate.notify.ReminderService;
import com.mywatchmate.sessions.dto.ScheduleRequest;
import com.mywatchmate.sessions.dto.ScheduleResponse;
import com.mywatchmate.watch.ContentItem;
import com.mywatchmate.watch.ContentItemRepository;
import com.mywatchmate.watch.Participant;
import com.mywatchmate.watch.Schedule;
import com.mywatchmate.watch.ScheduleRepository;
import com.mywatchmate.watch.Space;
import com.mywatchmate.watch.SpaceAccess;
import com.mywatchmate.watch.SpaceEvent;
import com.mywatchmate.watch.SpaceEvents;
import com.mywatchmate.watch.WatchSession;
import com.mywatchmate.watch.WatchSessionRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

@ExtendWith(MockitoExtension.class)
class ScheduleServiceTest {

    @Mock ScheduleRepository schedules;
    @Mock WatchSessionRepository sessions;
    @Mock ContentItemRepository contentItems;
    @Mock SpaceAccess access;
    @Mock SessionPlanner planner;
    @Mock ReminderService reminders;
    @Mock SessionViews views;
    @Mock SpaceEvents events;

    private ScheduleService service;
    private Space space;
    private Participant owner;
    private Participant tunde;
    private Participant bisi;
    private final ScheduleResponse response = new ScheduleResponse(UUID.randomUUID(), null, null, List.of(), "06:00",
        "Africa/Lagos", LocalDate.of(2026, 11, 2), null, true, null, null);

    @BeforeEach
    void setUp() {
        service = new ScheduleService(schedules, sessions, contentItems, access, planner, reminders, views, events,
            clockAt(MONDAY_MIDNIGHT));
        space = space(user("Ada"));
        owner = member(space, space.getOwner(), Participant.Role.OWNER);
        tunde = member(space, user("Tunde"), Participant.Role.MEMBER);
        bisi = guest(space, "Bisi");
        for (Participant p : List.of(owner, tunde, bisi)) {
            lenient().when(access.require(principalOf(p), space.getId())).thenReturn(membership(p));
        }
        lenient().when(views.schedule(any(Schedule.class))).thenReturn(response);
    }

    @Test
    void ownerCreatesAWeeklyPlanAndItsSessionsArePlannedAtOnce() {
        ContentItem video = video(space);
        when(contentItems.findByIdAndSpaceId(video.getId(), space.getId())).thenReturn(Optional.of(video));

        ScheduleResponse result = service.create(principalOf(owner), space.getId(), new ScheduleRequest("  Morning Devotion ",
            Set.of(MONDAY, FRIDAY), "06:00", " Africa/Lagos ", LocalDate.of(2026, 11, 2), LocalDate.of(2026, 12, 24), video.getId()));

        ArgumentCaptor<Schedule> saved = ArgumentCaptor.forClass(Schedule.class);
        verify(schedules).save(saved.capture());
        Schedule schedule = saved.getValue();
        assertThat(schedule.getRrule()).isEqualTo("FREQ=WEEKLY;BYDAY=MO,FR;BYHOUR=6;BYMINUTE=0");
        assertThat(schedule.getTimezone()).isEqualTo("Africa/Lagos");
        assertThat(schedule.getTitle()).isEqualTo("Morning Devotion");
        assertThat(schedule.getStartsOn()).isEqualTo(LocalDate.of(2026, 11, 2));
        assertThat(schedule.getEndsOn()).isEqualTo(LocalDate.of(2026, 12, 24));
        assertThat(schedule.getContentItem()).isSameAs(video);
        assertThat(schedule.getSpace()).isSameAs(space);
        verify(planner).plan(schedule);
        verify(events).publish(space.getId(), SpaceEvent.Type.SCHEDULE_UPDATED, response);
        assertThat(result).isSameAs(response);
    }

    @Test
    void membersCantCreateSchedules() {
        assertApiError(() -> service.create(principalOf(tunde), space.getId(), request()), HttpStatus.FORBIDDEN, "FORBIDDEN_ROLE");
        verify(schedules, never()).save(any());
    }

    @Test
    void endDateBeforeStartIsRefused() {
        assertApiError(() -> service.create(principalOf(owner), space.getId(), new ScheduleRequest(null, Set.of(MONDAY),
            "06:00", "Africa/Lagos", LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 1), null)),
            HttpStatus.BAD_REQUEST, "INVALID_SCHEDULE");
        verify(schedules, never()).save(any());
    }

    @Test
    void unknownTimezoneIsRefused() {
        assertApiError(() -> service.create(principalOf(owner), space.getId(), new ScheduleRequest(null, Set.of(MONDAY),
            "06:00", "Mars/Olympus", LocalDate.of(2026, 11, 2), null, null)), HttpStatus.BAD_REQUEST, "INVALID_TIMEZONE");
    }

    @Test
    void videoMustBeInThisRoom() {
        UUID elsewhere = UUID.randomUUID();
        when(contentItems.findByIdAndSpaceId(elsewhere, space.getId())).thenReturn(Optional.empty());

        assertApiError(() -> service.create(principalOf(owner), space.getId(), new ScheduleRequest(null, Set.of(MONDAY),
            "06:00", "Africa/Lagos", LocalDate.of(2026, 11, 2), null, elsewhere)), HttpStatus.NOT_FOUND, "CONTENT_NOT_FOUND");
    }

    @Test
    void archivedRoomsGetNoNewSchedules() {
        space.archive(MONDAY_MIDNIGHT);
        assertApiError(() -> service.create(principalOf(owner), space.getId(), request()), HttpStatus.CONFLICT, "SPACE_ARCHIVED");
    }

    @Test
    void everyoneIncludingGuestsCanSeeSchedules() {
        Schedule schedule = weekdaySchedule(space);
        when(schedules.findBySpaceIdAndActiveTrue(space.getId())).thenReturn(List.of(schedule));

        assertThat(service.list(principalOf(bisi), space.getId())).containsExactly(response);
    }

    @Test
    void replacingDropsUnstartedFutureSittingsThenPlansAgain() {
        Schedule schedule = weekdaySchedule(space);
        when(schedules.findByIdAndSpaceIdAndActiveTrue(schedule.getId(), space.getId())).thenReturn(Optional.of(schedule));

        service.replace(principalOf(owner), space.getId(), schedule.getId(), new ScheduleRequest("Evening", Set.of(FRIDAY),
            "19:30", "Europe/London", LocalDate.of(2026, 11, 6), null, null));

        assertThat(schedule.getRrule()).isEqualTo("FREQ=WEEKLY;BYDAY=FR;BYHOUR=19;BYMINUTE=30");
        assertThat(schedule.getTimezone()).isEqualTo("Europe/London");
        assertThat(schedule.getTitle()).isEqualTo("Evening");
        InOrder order = inOrder(sessions, planner);
        order.verify(sessions).deleteFutureOf(schedule.getId(), MONDAY_MIDNIGHT, WatchSession.Status.SCHEDULED);
        order.verify(planner).plan(schedule);
        verify(events).publish(eq(space.getId()), eq(SpaceEvent.Type.SCHEDULE_UPDATED), any());
    }

    @Test
    void replacingAnUnknownScheduleIs404() {
        UUID missing = UUID.randomUUID();
        when(schedules.findByIdAndSpaceIdAndActiveTrue(missing, space.getId())).thenReturn(Optional.empty());

        assertApiError(() -> service.replace(principalOf(owner), space.getId(), missing, request()),
            HttpStatus.NOT_FOUND, "SCHEDULE_NOT_FOUND");
        verifyNoInteractions(planner);
    }

    @Test
    void deletingStopsThePlanAndCancelsUpcomingSittingsAndReminders() {
        Schedule schedule = weekdaySchedule(space);
        when(schedules.findByIdAndSpaceIdAndActiveTrue(schedule.getId(), space.getId())).thenReturn(Optional.of(schedule));

        service.delete(principalOf(owner), space.getId(), schedule.getId());

        assertThat(schedule.isActive()).isFalse();
        verify(sessions).cancelFutureOf(schedule.getId(), MONDAY_MIDNIGHT, WatchSession.Status.SCHEDULED, WatchSession.Status.CANCELLED);
        verify(reminders).cancelForStoppedSchedule(schedule.getId());
        verify(events).publish(eq(space.getId()), eq(SpaceEvent.Type.SCHEDULE_UPDATED), any());
    }

    @Test
    void membersCantDeleteSchedules() {
        assertApiError(() -> service.delete(principalOf(tunde), space.getId(), UUID.randomUUID()), HttpStatus.FORBIDDEN, "FORBIDDEN_ROLE");
        verifyNoInteractions(sessions, reminders);
    }

    private static ScheduleRequest request() {
        return new ScheduleRequest(null, Set.of(MONDAY), "06:00", "Africa/Lagos", LocalDate.of(2026, 11, 2), null, null);
    }
}
