package com.mywatchmate.sessions;

import static com.mywatchmate.testsupport.Fixtures.session;
import static com.mywatchmate.testsupport.Fixtures.space;
import static com.mywatchmate.testsupport.Fixtures.user;
import static com.mywatchmate.testsupport.Fixtures.video;
import static com.mywatchmate.testsupport.Fixtures.weekdaySchedule;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.mywatchmate.auth.AuthPrincipal;
import com.mywatchmate.identity.User;
import com.mywatchmate.identity.UserRepository;
import com.mywatchmate.sessions.dto.ScheduleResponse;
import com.mywatchmate.sessions.dto.SessionResponse;
import com.mywatchmate.watch.Schedule;
import com.mywatchmate.watch.Space;
import com.mywatchmate.watch.WatchSession;
import com.mywatchmate.watch.WatchSessionRepository;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SessionViewsTest {

    private static final Instant SIX_AM_LAGOS = Instant.parse("2026-11-02T05:00:00Z");

    @Mock UserRepository users;
    @Mock WatchSessionRepository sessions;

    private SessionViews views;
    private Space space;

    @BeforeEach
    void setUp() {
        views = new SessionViews(users, sessions);
        space = space(user("Ada"));
    }

    @Test
    void usersGetTheirOwnTimezoneAndGuestsUtc() {
        User kemi = user("Kemi");
        kemi.setTimezone("Europe/London");
        when(users.findById(kemi.getId())).thenReturn(Optional.of(kemi));

        assertThat(views.zoneFor(AuthPrincipal.user(kemi.getId()))).isEqualTo(ZoneId.of("Europe/London"));
        assertThat(views.zoneFor(AuthPrincipal.guest(UUID.randomUUID(), space.getId()))).isEqualTo(ZoneId.of("UTC"));
    }

    @Test
    void sessionShowsUtcAndTheCallersLocalTime() {
        WatchSession prayer = session(space, SIX_AM_LAGOS);
        prayer.setTitle("Morning Devotion");
        prayer.setContentItem(video(space));

        SessionResponse lagos = views.session(prayer, ZoneId.of("Africa/Lagos"));

        assertThat(lagos.id()).isEqualTo(prayer.getId());
        assertThat(lagos.spaceId()).isEqualTo(space.getId());
        assertThat(lagos.spaceTitle()).isEqualTo("6am Prayer Room");
        assertThat(lagos.title()).isEqualTo("Morning Devotion");
        assertThat(lagos.status()).isEqualTo(WatchSession.Status.SCHEDULED);
        assertThat(lagos.scheduledAt()).isEqualTo(SIX_AM_LAGOS);
        assertThat(lagos.scheduledAtLocal()).isEqualTo(OffsetDateTime.parse("2026-11-02T06:00+01:00"));
        assertThat(lagos.localTimezone()).isEqualTo("Africa/Lagos");
        assertThat(lagos.content().title()).isEqualTo("Morning Prayer");
        assertThat(lagos.scheduleId()).isNull();

        assertThat(views.session(prayer).localTimezone()).isEqualTo("UTC");
    }

    @Test
    void scheduleShowsDaysInOrderTimeAsHhMmAndTheNextSitting() {
        Schedule schedule = weekdaySchedule(space);
        schedule.setRrule("FREQ=WEEKLY;BYDAY=FR,MO,WE;BYHOUR=6;BYMINUTE=5");
        WatchSession next = session(space, SIX_AM_LAGOS);
        when(sessions.findFirstByScheduleIdAndStatusOrderByScheduledAt(schedule.getId(), WatchSession.Status.SCHEDULED))
            .thenReturn(Optional.of(next));

        ScheduleResponse response = views.schedule(schedule);

        assertThat(response.days()).containsExactly(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY);
        assertThat(response.time()).isEqualTo("06:05");
        assertThat(response.timezone()).isEqualTo("Africa/Lagos");
        assertThat(response.title()).isEqualTo("Morning Devotion");
        assertThat(response.nextSessionAt()).isEqualTo(SIX_AM_LAGOS);
        assertThat(response.active()).isTrue();
    }

    @Test
    void stoppedScheduleHasNoNextSitting() {
        Schedule schedule = weekdaySchedule(space);
        schedule.setActive(false);

        assertThat(views.schedule(schedule).nextSessionAt()).isNull();
    }
}
