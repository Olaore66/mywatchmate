package com.mywatchmate.track;

import static org.assertj.core.api.Assertions.assertThat;

import com.mywatchmate.track.StreakCalculator.Result;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class StreakCalculatorTest {

    private static final LocalDate MON = LocalDate.of(2026, 11, 2);

    private static LocalDate day(int offset) {
        return MON.plusDays(offset);
    }

    @Test
    void nothingYetIsZero() {
        assertThat(StreakCalculator.compute(List.of(), List.of())).isEqualTo(new Result(0, 0, null));
    }

    @Test
    void attendedDaysInARowBuildTheStreak() {
        Result result = StreakCalculator.compute(List.of(day(0), day(1), day(2)), List.of(day(0), day(1), day(2)));

        assertThat(result.current()).isEqualTo(3);
        assertThat(result.longest()).isEqualTo(3);
        assertThat(result.lastAttendedOn()).isEqualTo(day(2));
    }

    @Test
    void twoSessionsOnOneDayCountOnce() {
        Result result = StreakCalculator.compute(List.of(day(0), day(0), day(1)), List.of(day(0), day(0), day(1)));

        assertThat(result.current()).isEqualTo(2);
    }

    @Test
    void daysWithNothingOnDontBreakIt() {
        // A Sundays-only room: three Sundays in a row is a streak of 3
        Result result = StreakCalculator.compute(List.of(day(6), day(13), day(20)), List.of(day(6), day(13), day(20)));

        assertThat(result.current()).isEqualTo(3);
    }

    @Test
    void aMissedDayResetsItButThePersonalBestStays() {
        List<LocalDate> expected = List.of(day(0), day(1), day(2), day(3), day(4));
        Result result = StreakCalculator.compute(List.of(day(0), day(1), day(2), day(4)), expected);

        assertThat(result.current()).isEqualTo(1);
        assertThat(result.longest()).isEqualTo(3);
        assertThat(result.lastAttendedOn()).isEqualTo(day(4));
    }

    @Test
    void missingTheLatestSessionMeansZeroNow() {
        Result result = StreakCalculator.compute(List.of(day(0), day(1)), List.of(day(0), day(1), day(2)));

        assertThat(result.current()).isZero();
        assertThat(result.longest()).isEqualTo(2);
        assertThat(result.lastAttendedOn()).isEqualTo(day(1));
    }

    @Test
    void attendingAnotherSessionThatDayCoversAMissedOne() {
        // Day 1: missed the morning room, made the evening one
        Result result = StreakCalculator.compute(List.of(day(0), day(1)), List.of(day(0), day(1), day(1)));

        assertThat(result.current()).isEqualTo(2);
    }

    @Test
    void attendingWithoutBeingExpectedStillCounts() {
        // Joined a room mid-session and stayed: not "expected", but attended
        Result result = StreakCalculator.compute(List.of(day(0), day(1)), List.of(day(0)));

        assertThat(result.current()).isEqualTo(2);
    }

    @Test
    void orderOfTheInputDoesntMatter() {
        Result result = StreakCalculator.compute(List.of(day(2), day(0), day(1)), List.of(day(1), day(2), day(0)));

        assertThat(result.current()).isEqualTo(3);
    }
}
