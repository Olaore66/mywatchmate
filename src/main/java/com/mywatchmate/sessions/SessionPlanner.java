package com.mywatchmate.sessions;

import com.mywatchmate.notify.ReminderService;
import com.mywatchmate.watch.Schedule;
import com.mywatchmate.watch.WatchSession;
import com.mywatchmate.watch.WatchSessionRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Turns a schedule into real sessions for the next {@code generateDays} days, with their reminders.
 * Safe to repeat: sittings that already exist are skipped (and the database refuses duplicates).
 * Call inside a transaction.
 */
@Component
@RequiredArgsConstructor
public class SessionPlanner {

    private final WatchSessionRepository sessions;
    private final ReminderService reminders;
    private final SessionProperties props;
    private final Clock clock;

    /** @return how many new sessions were created */
    public int plan(Schedule schedule) {
        if (!schedule.isActive() || schedule.getSpace().isArchived()) {
            return 0;
        }
        Instant now = clock.instant();
        Instant until = now.plus(Duration.ofDays(props.generateDays()));
        int created = 0;
        for (Instant at : Recurrence.parse(schedule.getRrule()).occurrences(
                ZoneId.of(schedule.getTimezone()), schedule.getStartsOn(), schedule.getEndsOn(), now, until)) {
            if (!sessions.existsByScheduleIdAndScheduledAt(schedule.getId(), at)) {
                WatchSession session = sessions.save(WatchSession.fromSchedule(schedule, at));
                reminders.scheduleFor(session);
                created++;
            }
        }
        return created;
    }
}
