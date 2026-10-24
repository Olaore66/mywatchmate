package com.mywatchmate.track;

import static com.mywatchmate.testsupport.Fixtures.MONDAY_MIDNIGHT;
import static com.mywatchmate.testsupport.Fixtures.assertApiError;
import static com.mywatchmate.testsupport.Fixtures.clockAt;
import static com.mywatchmate.testsupport.Fixtures.user;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mywatchmate.identity.User;
import com.mywatchmate.identity.UserRepository;
import com.mywatchmate.track.dto.StreakResponse;
import com.mywatchmate.watch.WatchSession;
import com.mywatchmate.watch.WatchSessionRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

@ExtendWith(MockitoExtension.class)
class StreakServiceTest {

    @Mock StreakRepository streaks;
    @Mock UserRepository users;
    @Mock AttendanceRepository attendance;
    @Mock WatchSessionRepository sessions;

    private StreakService service;
    private User ada;

    @BeforeEach
    void setUp() {
        service = new StreakService(streaks, users, attendance, sessions, clockAt(MONDAY_MIDNIGHT));
        ada = user("Ada");
        ada.setTimezone("Africa/Lagos");
        lenient().when(users.findById(ada.getId())).thenReturn(Optional.of(ada));
    }

    @Test
    void rebuildsFromHistoryAndSavesANewStreak() {
        // Sat, Sun, Mon mornings in Lagos
        List<Instant> mornings = List.of(Instant.parse("2026-10-31T05:00:00Z"), Instant.parse("2026-11-01T05:00:00Z"),
            Instant.parse("2026-11-02T05:00:00Z"));
        when(attendance.findCountedStartsForUser(ada.getId())).thenReturn(mornings);
        when(sessions.findExpectedStartsForUser(ada.getId(), WatchSession.Status.ENDED)).thenReturn(mornings);
        when(streaks.findById(ada.getId())).thenReturn(Optional.empty());
        when(streaks.save(any(Streak.class))).thenAnswer(i -> i.getArgument(0));

        Streak streak = service.recompute(ada.getId());

        assertThat(streak.getCurrentCount()).isEqualTo(3);
        assertThat(streak.getLongestCount()).isEqualTo(3);
        assertThat(streak.getLastAttendedOn()).isEqualTo(LocalDate.of(2026, 11, 2));
        ArgumentCaptor<Streak> saved = ArgumentCaptor.forClass(Streak.class);
        verify(streaks).save(saved.capture());
        assertThat(saved.getValue().getUser()).isSameAs(ada);
    }

    @Test
    void daysFollowTheUsersOwnTimezone() {
        // 23:30 and 00:30 UTC: two different days in UTC, but 00:30 and 01:30 on the same day in Lagos
        List<Instant> lateNights = List.of(Instant.parse("2026-11-01T23:30:00Z"), Instant.parse("2026-11-02T00:30:00Z"));
        when(attendance.findCountedStartsForUser(ada.getId())).thenReturn(lateNights);
        when(sessions.findExpectedStartsForUser(ada.getId(), WatchSession.Status.ENDED)).thenReturn(lateNights);
        Streak existing = new Streak(ada);
        when(streaks.findById(ada.getId())).thenReturn(Optional.of(existing));

        assertThat(service.recompute(ada.getId()).getCurrentCount()).as("one day in Lagos").isEqualTo(1);

        ada.setTimezone("UTC");
        assertThat(service.recompute(ada.getId()).getCurrentCount()).as("two days in UTC").isEqualTo(2);
        verify(streaks, never()).save(any());
    }

    @Test
    void aMissedSessionResetsTheStoredStreak() {
        Instant sat = Instant.parse("2026-10-31T05:00:00Z");
        Instant sun = Instant.parse("2026-11-01T05:00:00Z");
        when(attendance.findCountedStartsForUser(ada.getId())).thenReturn(List.of(sat));
        when(sessions.findExpectedStartsForUser(ada.getId(), WatchSession.Status.ENDED)).thenReturn(List.of(sat, sun));
        when(streaks.findById(ada.getId())).thenReturn(Optional.of(new Streak(ada)));

        Streak streak = service.recompute(ada.getId());

        assertThat(streak.getCurrentCount()).isZero();
        assertThat(streak.getLongestCount()).isEqualTo(1);
    }

    @Test
    void myStreakIncludesThisWeekAndTimezone() {
        Streak stored = new Streak(ada);
        stored.apply(new StreakCalculator.Result(4, 9, LocalDate.of(2026, 11, 1)));
        when(streaks.findById(ada.getId())).thenReturn(Optional.of(stored));
        when(attendance.countCountedSince(ada.getId(), MONDAY_MIDNIGHT.minusSeconds(7 * 24 * 3600))).thenReturn(3L);

        StreakResponse mine = service.mine(ada.getId());

        assertThat(mine).isEqualTo(new StreakResponse(4, 9, LocalDate.of(2026, 11, 1), 3, "Africa/Lagos"));
    }

    @Test
    void noStreakYetIsAllZeros() {
        when(streaks.findById(ada.getId())).thenReturn(Optional.empty());

        StreakResponse mine = service.mine(ada.getId());

        assertThat(mine.current()).isZero();
        assertThat(mine.longest()).isZero();
        assertThat(mine.lastAttendedOn()).isNull();
        assertThat(service.currentFor(ada.getId())).isZero();
    }

    @Test
    void deletedAccountsHaveNoStreak() {
        UUID gone = UUID.randomUUID();
        when(users.findById(gone)).thenReturn(Optional.empty());

        assertApiError(() -> service.mine(gone), HttpStatus.NOT_FOUND, "USER_NOT_FOUND");
        assertApiError(() -> service.recompute(gone), HttpStatus.NOT_FOUND, "USER_NOT_FOUND");
    }
}
