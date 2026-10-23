package com.mywatchmate.sessions;

import com.mywatchmate.auth.AuthPrincipal;
import com.mywatchmate.common.ApiException;
import com.mywatchmate.common.Timezones;
import com.mywatchmate.notify.ReminderService;
import com.mywatchmate.sessions.dto.ScheduleRequest;
import com.mywatchmate.sessions.dto.ScheduleResponse;
import com.mywatchmate.watch.ContentItem;
import com.mywatchmate.watch.ContentItemRepository;
import com.mywatchmate.watch.Schedule;
import com.mywatchmate.watch.ScheduleRepository;
import com.mywatchmate.watch.SpaceAccess;
import com.mywatchmate.watch.SpaceAccess.Membership;
import com.mywatchmate.watch.SpaceEvent;
import com.mywatchmate.watch.SpaceEvents;
import com.mywatchmate.watch.WatchSession;
import com.mywatchmate.watch.WatchSessionRepository;
import java.time.Clock;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** A room's repeating plans. Owners/admins manage them; everyone in the room can see them. */
@Service
@RequiredArgsConstructor
public class ScheduleService {

    private final ScheduleRepository schedules;
    private final WatchSessionRepository sessions;
    private final ContentItemRepository contentItems;
    private final SpaceAccess access;
    private final SessionPlanner planner;
    private final ReminderService reminders;
    private final SessionViews views;
    private final SpaceEvents events;
    private final Clock clock;

    /** Saves the plan and immediately creates its sessions for the coming weeks. */
    @Transactional
    public ScheduleResponse create(AuthPrincipal principal, UUID spaceId, ScheduleRequest request) {
        Membership host = access.require(principal, spaceId).requireOwnerOrAdmin().requireNotArchived();
        Schedule schedule = new Schedule(host.space(), "FREQ=WEEKLY;BYDAY=MO", "UTC", request.startsOn());
        apply(schedule, request, spaceId);
        schedules.save(schedule);
        planner.plan(schedule);
        return announce(schedule);
    }

    @Transactional(readOnly = true)
    public List<ScheduleResponse> list(AuthPrincipal principal, UUID spaceId) {
        access.require(principal, spaceId);
        return schedules.findBySpaceIdAndActiveTrue(spaceId).stream().map(views::schedule).toList();
    }

    /** Replaces the plan. Future sittings that haven't started are recreated (with fresh reminders). */
    @Transactional
    public ScheduleResponse replace(AuthPrincipal principal, UUID spaceId, UUID scheduleId, ScheduleRequest request) {
        access.require(principal, spaceId).requireOwnerOrAdmin().requireNotArchived();
        Schedule schedule = find(spaceId, scheduleId);
        apply(schedule, request, spaceId);
        sessions.deleteFutureOf(scheduleId, clock.instant(), WatchSession.Status.SCHEDULED);
        planner.plan(schedule);
        return announce(schedule);
    }

    /** Stops the plan. Its future sittings are cancelled (kept for history) along with their reminders. */
    @Transactional
    public void delete(AuthPrincipal principal, UUID spaceId, UUID scheduleId) {
        access.require(principal, spaceId).requireOwnerOrAdmin();
        Schedule schedule = find(spaceId, scheduleId);
        schedule.setActive(false);
        sessions.cancelFutureOf(scheduleId, clock.instant(), WatchSession.Status.SCHEDULED, WatchSession.Status.CANCELLED);
        reminders.cancelForStoppedSchedule(scheduleId);
        announce(schedule);
    }

    private void apply(Schedule schedule, ScheduleRequest request, UUID spaceId) {
        if (request.endsOn() != null && request.endsOn().isBefore(request.startsOn())) {
            throw ApiException.badRequest("INVALID_SCHEDULE", "The end date is before the start date.");
        }
        Recurrence pattern = new Recurrence(request.days(), LocalTime.parse(request.time()));
        schedule.setRrule(pattern.toRrule());
        schedule.setTimezone(Timezones.normalize(request.timezone()));
        schedule.setStartsOn(request.startsOn());
        schedule.setEndsOn(request.endsOn());
        schedule.setTitle(request.title() == null || request.title().isBlank() ? null : request.title().trim());
        schedule.setContentItem(request.contentId() == null ? null : contentIn(spaceId, request.contentId()));
    }

    private ScheduleResponse announce(Schedule schedule) {
        ScheduleResponse response = views.schedule(schedule);
        events.publish(schedule.getSpace().getId(), SpaceEvent.Type.SCHEDULE_UPDATED, response);
        return response;
    }

    private Schedule find(UUID spaceId, UUID scheduleId) {
        return schedules.findByIdAndSpaceIdAndActiveTrue(scheduleId, spaceId)
            .orElseThrow(() -> ApiException.notFound("SCHEDULE_NOT_FOUND", "That schedule isn't in this room."));
    }

    private ContentItem contentIn(UUID spaceId, UUID contentId) {
        return contentItems.findByIdAndSpaceId(contentId, spaceId)
            .orElseThrow(() -> ApiException.notFound("CONTENT_NOT_FOUND", "That video isn't in this room."));
    }
}
