package com.mywatchmate.track;

import java.time.LocalDate;
import java.util.Collection;
import java.util.Set;
import java.util.TreeSet;

/**
 * The streak rules, in days (in the user's timezone):
 * <ul>
 *   <li>a day with at least one counted session adds 1, however many sessions that day;</li>
 *   <li>a day with a session they were expected at but didn't count for any resets it to 0;</li>
 *   <li>days with nothing on in any of their rooms don't affect it, so a Sundays-only room still builds a streak.</li>
 * </ul>
 */
public final class StreakCalculator {

    public record Result(int current, int longest, LocalDate lastAttendedOn) {
        public static final Result NONE = new Result(0, 0, null);
    }

    private StreakCalculator() {
    }

    /**
     * @param attendedDays days with at least one counted session
     * @param expectedDays days with at least one session the user was a member for (attended or not)
     */
    public static Result compute(Collection<LocalDate> attendedDays, Collection<LocalDate> expectedDays) {
        Set<LocalDate> attended = new TreeSet<>(attendedDays);
        TreeSet<LocalDate> days = new TreeSet<>(expectedDays);
        days.addAll(attended);

        int run = 0;
        int longest = 0;
        LocalDate lastAttended = null;
        for (LocalDate day : days) {
            if (attended.contains(day)) {
                run++;
                longest = Math.max(longest, run);
                lastAttended = day;
            } else {
                run = 0;
            }
        }
        return new Result(run, longest, lastAttended);
    }
}
