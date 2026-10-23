package com.mywatchmate.sessions;

import com.mywatchmate.auth.AuthPrincipal;
import com.mywatchmate.identity.UserRepository;
import com.mywatchmate.sessions.dto.ScheduleResponse;
import com.mywatchmate.sessions.dto.SessionResponse;
import com.mywatchmate.watch.ContentItem;
import com.mywatchmate.watch.Schedule;
import com.mywatchmate.watch.WatchSession;
import com.mywatchmate.watch.WatchSessionRepository;
import com.mywatchmate.watch.dto.ContentResponse;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.TreeSet;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Shapes sessions and schedules for the API. Call inside a transaction. */
@Component
@RequiredArgsConstructor
public class SessionViews {

    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");
    private static final ZoneId UTC = ZoneId.of("UTC");

    private final UserRepository users;
    private final WatchSessionRepository sessions;

    /** The caller's own timezone; guests have none, so UTC. */
    public ZoneId zoneFor(AuthPrincipal principal) {
        if (principal.isGuest()) {
            return UTC;
        }
        return users.findById(principal.userId()).map(u -> ZoneId.of(u.getTimezone())).orElse(UTC);
    }

    public SessionResponse session(WatchSession session, ZoneId zone) {
        ContentItem content = session.getContentItem();
        Instant at = session.getScheduledAt();
        return new SessionResponse(session.getId(), session.getSpace().getId(), session.getSpace().getTitle(),
            session.getSchedule() == null ? null : session.getSchedule().getId(), session.getTitle(), session.getStatus(),
            at, at == null ? null : at.atZone(zone).toOffsetDateTime(), zone.getId(),
            session.getStartedAt(), session.getEndedAt(), content == null ? null : ContentResponse.from(content));
    }

    /** For room-wide events, which go to everyone: times in UTC. */
    public SessionResponse session(WatchSession session) {
        return session(session, UTC);
    }

    public ScheduleResponse schedule(Schedule schedule) {
        Recurrence pattern = Recurrence.parse(schedule.getRrule());
        Instant next = sessions.findFirstByScheduleIdAndStatusOrderByScheduledAt(schedule.getId(), WatchSession.Status.SCHEDULED)
            .map(WatchSession::getScheduledAt).orElse(null);
        ContentItem content = schedule.getContentItem();
        return new ScheduleResponse(schedule.getId(), schedule.getSpace().getId(), schedule.getTitle(),
            List.copyOf(new TreeSet<>(pattern.days())), pattern.time().format(HH_MM), schedule.getTimezone(),
            schedule.getStartsOn(), schedule.getEndsOn(), schedule.isActive(),
            content == null ? null : ContentResponse.from(content), schedule.isActive() ? next : null);
    }
}
