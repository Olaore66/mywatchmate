package com.mywatchmate.sessions;

import static java.time.DayOfWeek.FRIDAY;
import static java.time.DayOfWeek.MONDAY;
import static java.time.DayOfWeek.SATURDAY;
import static java.time.DayOfWeek.SUNDAY;
import static java.time.DayOfWeek.THURSDAY;
import static java.time.DayOfWeek.TUESDAY;
import static java.time.DayOfWeek.WEDNESDAY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mywatchmate.common.ApiException;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RecurrenceTest {

    private static final ZoneId LAGOS = ZoneId.of("Africa/Lagos");    // UTC+1 all year
    private static final ZoneId LONDON = ZoneId.of("Europe/London");  // UTC+0 / +1 (BST)
    private static final Set<DayOfWeek> WEEKDAYS = EnumSet.of(MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY);

    @Nested
    class RuleText {

        @Test
        void writesAStandardWeeklyRuleInDayOrder() {
            Recurrence weekdays = new Recurrence(EnumSet.of(FRIDAY, MONDAY, WEDNESDAY), LocalTime.of(6, 0));
            assertThat(weekdays.toRrule()).isEqualTo("FREQ=WEEKLY;BYDAY=MO,WE,FR;BYHOUR=6;BYMINUTE=0");
        }

        @Test
        void readsBackWhatItWrites() {
            Recurrence original = new Recurrence(EnumSet.of(SATURDAY, SUNDAY), LocalTime.of(17, 30));
            assertThat(Recurrence.parse(original.toRrule())).isEqualTo(original);
        }

        @Test
        void parsingIsLenientAboutOrderAndCase() {
            assertThat(Recurrence.parse("byminute=15;byday=tu,th;freq=weekly;byhour=20"))
                .isEqualTo(new Recurrence(EnumSet.of(TUESDAY, THURSDAY), LocalTime.of(20, 15)));
        }

        @ParameterizedTest
        @ValueSource(strings = {"FREQ=DAILY;BYHOUR=6", "FREQ=WEEKLY;BYHOUR=6", "FREQ=WEEKLY;BYDAY=XX", "nonsense"})
        void unsupportedRulesAreRefused(String rule) {
            assertThatThrownBy(() -> Recurrence.parse(rule)).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void needsAtLeastOneDay() {
            assertThatThrownBy(() -> new Recurrence(EnumSet.noneOf(DayOfWeek.class), LocalTime.NOON))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("INVALID_SCHEDULE"));
        }

        @Test
        void secondsAreDropped() {
            assertThat(new Recurrence(WEEKDAYS, LocalTime.of(6, 0, 45)).time()).isEqualTo(LocalTime.of(6, 0));
        }
    }

    @Nested
    class Occurrences {

        @Test
        void weekdaysAt6amLagosAre5amUtc() {
            Recurrence prayer = new Recurrence(WEEKDAYS, LocalTime.of(6, 0));
            // Mon 2 Nov 2026 to Mon 9 Nov 2026 (exclusive)
            List<Instant> times = prayer.occurrences(LAGOS, LocalDate.of(2026, 11, 1), null,
                Instant.parse("2026-11-02T00:00:00Z"), Instant.parse("2026-11-09T00:00:00Z"));

            assertThat(times).containsExactly(
                Instant.parse("2026-11-02T05:00:00Z"), Instant.parse("2026-11-03T05:00:00Z"),
                Instant.parse("2026-11-04T05:00:00Z"), Instant.parse("2026-11-05T05:00:00Z"),
                Instant.parse("2026-11-06T05:00:00Z"));
        }

        @Test
        void windowStartIsInclusiveAndEndIsExclusive() {
            Recurrence daily = new Recurrence(EnumSet.allOf(DayOfWeek.class), LocalTime.of(6, 0));

            List<Instant> times = daily.occurrences(LAGOS, LocalDate.of(2026, 1, 1), null,
                Instant.parse("2026-11-02T05:00:00Z"), Instant.parse("2026-11-04T05:00:00Z"));

            assertThat(times).containsExactly(Instant.parse("2026-11-02T05:00:00Z"), Instant.parse("2026-11-03T05:00:00Z"));
        }

        @Test
        void nothingBeforeTheStartDateOrAfterTheEndDate() {
            Recurrence daily = new Recurrence(EnumSet.allOf(DayOfWeek.class), LocalTime.of(6, 0));

            List<Instant> times = daily.occurrences(LAGOS, LocalDate.of(2026, 11, 4), LocalDate.of(2026, 11, 6),
                Instant.parse("2026-11-01T00:00:00Z"), Instant.parse("2026-11-30T00:00:00Z"));

            assertThat(times).containsExactly(Instant.parse("2026-11-04T05:00:00Z"),
                Instant.parse("2026-11-05T05:00:00Z"), Instant.parse("2026-11-06T05:00:00Z"));
        }

        @Test
        void aLateEveningScheduleInATimezoneAheadOfUtcLandsOnTheRightDay() {
            // Sunday 00:30 in Lagos is Saturday 23:30 UTC
            Recurrence sundays = new Recurrence(EnumSet.of(SUNDAY), LocalTime.of(0, 30));

            List<Instant> times = sundays.occurrences(LAGOS, LocalDate.of(2026, 11, 1), null,
                Instant.parse("2026-11-07T00:00:00Z"), Instant.parse("2026-11-09T00:00:00Z"));

            assertThat(times).containsExactly(Instant.parse("2026-11-07T23:30:00Z"));
        }

        @Test
        void lagosTimeNeverMovesWhenLondonChangesClocks() {
            // London's clocks go back on Sun 25 Oct 2026; Lagos has no daylight saving
            Recurrence prayer = new Recurrence(WEEKDAYS, LocalTime.of(6, 0));

            List<Instant> times = prayer.occurrences(LAGOS, LocalDate.of(2026, 10, 1), null,
                Instant.parse("2026-10-22T00:00:00Z"), Instant.parse("2026-10-28T00:00:00Z"));

            assertThat(times).containsExactly(Instant.parse("2026-10-22T05:00:00Z"), Instant.parse("2026-10-23T05:00:00Z"),
                Instant.parse("2026-10-26T05:00:00Z"), Instant.parse("2026-10-27T05:00:00Z"));
            // Seen from London: 06:00 before the change, 05:00 after it
            assertThat(times.get(1).atZone(LONDON).toLocalTime()).isEqualTo(LocalTime.of(6, 0));
            assertThat(times.get(2).atZone(LONDON).toLocalTime()).isEqualTo(LocalTime.of(5, 0));
        }

        @Test
        void londonScheduleKeepsItsLocalTimeAcrossTheClockChange() {
            Recurrence weekdays = new Recurrence(WEEKDAYS, LocalTime.of(19, 0));

            List<Instant> times = weekdays.occurrences(LONDON, LocalDate.of(2026, 10, 1), null,
                Instant.parse("2026-10-23T00:00:00Z"), Instant.parse("2026-10-27T00:00:00Z"));

            // Fri 23 Oct 19:00 BST = 18:00Z; Mon 26 Oct 19:00 GMT = 19:00Z
            assertThat(times).containsExactly(Instant.parse("2026-10-23T18:00:00Z"), Instant.parse("2026-10-26T19:00:00Z"));
        }

        @Test
        void aTimeSkippedWhenClocksGoForwardMovesLaterByTheGap() {
            // Sun 29 Mar 2026 in London, 01:00 jumps to 02:00: 01:30 doesn't exist that day
            Recurrence sundays = new Recurrence(EnumSet.of(SUNDAY), LocalTime.of(1, 30));

            List<Instant> times = sundays.occurrences(LONDON, LocalDate.of(2026, 1, 1), null,
                Instant.parse("2026-03-28T00:00:00Z"), Instant.parse("2026-03-30T00:00:00Z"));

            assertThat(times).containsExactly(Instant.parse("2026-03-29T01:30:00Z")); // 02:30 BST
            assertThat(times.get(0).atZone(LONDON).toLocalTime()).isEqualTo(LocalTime.of(2, 30));
        }

        @Test
        void aTimeThatHappensTwiceWhenClocksGoBackUsesTheFirst() {
            // Sun 25 Oct 2026 in London, 01:00–02:00 happens twice: first in BST, then GMT
            Recurrence sundays = new Recurrence(EnumSet.of(SUNDAY), LocalTime.of(1, 30));

            List<Instant> times = sundays.occurrences(LONDON, LocalDate.of(2026, 1, 1), null,
                Instant.parse("2026-10-24T00:00:00Z"), Instant.parse("2026-10-26T00:00:00Z"));

            assertThat(times).containsExactly(Instant.parse("2026-10-25T00:30:00Z"));
        }

        @Test
        void emptyWhenTheScheduleHasEnded() {
            Recurrence prayer = new Recurrence(WEEKDAYS, LocalTime.of(6, 0));

            assertThat(prayer.occurrences(LAGOS, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 6, 30),
                Instant.parse("2026-11-01T00:00:00Z"), Instant.parse("2026-11-15T00:00:00Z"))).isEmpty();
        }

        @Test
        void fourteenDaysOfWeekdaysIsTenSittings() {
            Recurrence prayer = new Recurrence(WEEKDAYS, LocalTime.of(6, 0));
            Instant from = Instant.parse("2026-11-02T00:00:00Z");

            assertThat(prayer.occurrences(LAGOS, LocalDate.of(2026, 11, 1), null, from,
                from.plus(java.time.Duration.ofDays(14)))).hasSize(10);
        }
    }
}
