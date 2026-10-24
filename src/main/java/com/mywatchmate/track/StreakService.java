package com.mywatchmate.track;

import com.mywatchmate.common.ApiException;
import com.mywatchmate.identity.User;
import com.mywatchmate.identity.UserRepository;
import com.mywatchmate.track.dto.StreakResponse;
import com.mywatchmate.watch.WatchSession;
import com.mywatchmate.watch.WatchSessionRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Keeps each user's streak up to date. It's rebuilt from their whole attendance history (rather than
 * nudged up or down) so it's always right: after a session ends, after a guest signs up, and so on.
 */
@Service
@RequiredArgsConstructor
public class StreakService {

    private final StreakRepository streaks;
    private final UserRepository users;
    private final AttendanceRepository attendance;
    private final WatchSessionRepository sessions;
    private final Clock clock;

    /** Rebuilds and saves the user's streak. Call inside a transaction. */
    public Streak recompute(UUID userId) {
        User user = users.findById(userId)
            .orElseThrow(() -> ApiException.notFound("USER_NOT_FOUND", "This account no longer exists."));
        ZoneId zone = ZoneId.of(user.getTimezone());
        List<LocalDate> attended = days(attendance.findCountedStartsForUser(userId), zone);
        List<LocalDate> expected = days(sessions.findExpectedStartsForUser(userId, WatchSession.Status.ENDED), zone);

        Streak streak = streaks.findById(userId).orElseGet(() -> streaks.save(new Streak(user)));
        streak.apply(StreakCalculator.compute(attended, expected));
        return streak;
    }

    @Transactional(readOnly = true)
    public StreakResponse mine(UUID userId) {
        User user = users.findById(userId)
            .orElseThrow(() -> ApiException.notFound("USER_NOT_FOUND", "This account no longer exists."));
        Instant weekAgo = clock.instant().minus(Duration.ofDays(7));
        long thisWeek = attendance.countCountedSince(userId, weekAgo);
        return streaks.findById(userId)
            .map(s -> new StreakResponse(s.getCurrentCount(), s.getLongestCount(), s.getLastAttendedOn(), thisWeek,
                user.getTimezone()))
            .orElseGet(() -> new StreakResponse(0, 0, null, thisWeek, user.getTimezone()));
    }

    /** For room details. Call inside a transaction. */
    public int currentFor(UUID userId) {
        return streaks.findById(userId).map(Streak::getCurrentCount).orElse(0);
    }

    private static List<LocalDate> days(List<Instant> moments, ZoneId zone) {
        return moments.stream().map(m -> m.atZone(zone).toLocalDate()).toList();
    }
}
