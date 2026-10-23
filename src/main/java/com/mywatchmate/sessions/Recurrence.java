package com.mywatchmate.sessions;

import com.mywatchmate.common.ApiException;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A weekly pattern: which days, and what time on those days (in the schedule's timezone).
 * Stored as a standard RFC 5545 rule, e.g. {@code FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR;BYHOUR=6;BYMINUTE=0},
 * so it can be exported to calendars later; only this weekly subset is supported.
 */
public record Recurrence(Set<DayOfWeek> days, LocalTime time) {

    private static final Map<DayOfWeek, String> CODES = Map.of(
        DayOfWeek.MONDAY, "MO", DayOfWeek.TUESDAY, "TU", DayOfWeek.WEDNESDAY, "WE", DayOfWeek.THURSDAY, "TH",
        DayOfWeek.FRIDAY, "FR", DayOfWeek.SATURDAY, "SA", DayOfWeek.SUNDAY, "SU");

    public Recurrence {
        if (days == null || days.isEmpty()) {
            throw ApiException.badRequest("INVALID_SCHEDULE", "Pick at least one day.");
        }
        days = Collections.unmodifiableSet(EnumSet.copyOf(days));
        time = time.withSecond(0).withNano(0);
    }

    public String toRrule() {
        String byDay = days.stream().sorted().map(CODES::get).collect(Collectors.joining(","));
        return "FREQ=WEEKLY;BYDAY=" + byDay + ";BYHOUR=" + time.getHour() + ";BYMINUTE=" + time.getMinute();
    }

    public static Recurrence parse(String rrule) {
        Map<String, String> parts = new HashMap<>();
        for (String part : rrule.split(";")) {
            int eq = part.indexOf('=');
            if (eq > 0) {
                parts.put(part.substring(0, eq).trim().toUpperCase(), part.substring(eq + 1).trim().toUpperCase());
            }
        }
        if (!"WEEKLY".equals(parts.get("FREQ")) || !parts.containsKey("BYDAY")) {
            throw new IllegalArgumentException("Unsupported recurrence rule: " + rrule);
        }
        Set<DayOfWeek> days = EnumSet.noneOf(DayOfWeek.class);
        for (String code : parts.get("BYDAY").split(",")) {
            days.add(CODES.entrySet().stream().filter(e -> e.getValue().equals(code)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown day " + code + " in " + rrule)).getKey());
        }
        int hour = Integer.parseInt(parts.getOrDefault("BYHOUR", "0"));
        int minute = Integer.parseInt(parts.getOrDefault("BYMINUTE", "0"));
        return new Recurrence(days, LocalTime.of(hour, minute));
    }

    /**
     * Every occurrence in [from, to), between startsOn and endsOn (inclusive dates, endsOn may be null),
     * worked out in the schedule's own timezone. Daylight saving: a time that doesn't exist that day
     * (clocks jump forward) moves forward by the gap; a time that happens twice uses the first.
     */
    public List<Instant> occurrences(ZoneId zone, LocalDate startsOn, LocalDate endsOn, Instant from, Instant to) {
        List<Instant> result = new ArrayList<>();
        LocalDate first = later(startsOn, from.atZone(zone).toLocalDate().minusDays(1));
        LocalDate last = to.atZone(zone).toLocalDate().plusDays(1);
        if (endsOn != null && endsOn.isBefore(last)) {
            last = endsOn;
        }
        for (LocalDate date = first; !date.isAfter(last); date = date.plusDays(1)) {
            if (days.contains(date.getDayOfWeek())) {
                Instant at = ZonedDateTime.of(date, time, zone).toInstant();
                if (!at.isBefore(from) && at.isBefore(to)) {
                    result.add(at);
                }
            }
        }
        return result;
    }

    private static LocalDate later(LocalDate a, LocalDate b) {
        return a.isAfter(b) ? a : b;
    }
}
