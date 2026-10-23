package com.mywatchmate.notify;

import static com.mywatchmate.testsupport.Fixtures.session;
import static com.mywatchmate.testsupport.Fixtures.space;
import static com.mywatchmate.testsupport.Fixtures.user;
import static org.assertj.core.api.Assertions.assertThat;

import com.mywatchmate.identity.User;
import com.mywatchmate.watch.Space;
import com.mywatchmate.watch.WatchSession;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ReminderEmailTest {

    private static final Instant SIX_AM_LAGOS = Instant.parse("2026-11-02T05:00:00Z");

    private final Space space = space(user("Ada"));

    @Test
    void timeIsShownInTheReadersOwnTimezone() {
        User lagos = user("Tunde");
        lagos.setTimezone("Africa/Lagos");
        User london = user("Kemi");
        london.setTimezone("Europe/London");
        WatchSession prayer = session(space, SIX_AM_LAGOS);

        assertThat(ReminderEmail.render(lagos, prayer, "https://app", SIX_AM_LAGOS.minusSeconds(600)).text())
            .contains("When: Mon 2 Nov, 06:00 (Africa/Lagos)");
        assertThat(ReminderEmail.render(london, prayer, "https://app", SIX_AM_LAGOS.minusSeconds(600)).text())
            .contains("When: Mon 2 Nov, 05:00 (Europe/London)");
    }

    @Test
    void namesTheSessionTheRoomAndLinksToIt() {
        User tunde = user("Tunde");
        WatchSession prayer = session(space, SIX_AM_LAGOS);
        prayer.setTitle("Morning Devotion");

        EmailMessage email = ReminderEmail.render(tunde, prayer, "https://app.mywatchmate.com", SIX_AM_LAGOS.minusSeconds(600));

        assertThat(email.to()).isEqualTo("tunde@example.com");
        assertThat(email.subject()).isEqualTo("Starting in 10 minutes: 6am Prayer Room");
        assertThat(email.text())
            .startsWith("Hi Tunde,")
            .contains("\"Morning Devotion\" in \"6am Prayer Room\" starts in 10 minutes.")
            .contains("Join: https://app.mywatchmate.com/spaces/" + space.getId());
    }

    @Test
    void untitledSessionsUseTheRoomName() {
        EmailMessage email = ReminderEmail.render(user("Tunde"), session(space, SIX_AM_LAGOS), "https://app", SIX_AM_LAGOS.minusSeconds(600));

        assertThat(email.text()).contains("\"6am Prayer Room\" starts in 10 minutes.");
    }

    @Test
    void liveSessionsSayItHasStarted() {
        WatchSession prayer = session(space, SIX_AM_LAGOS);
        prayer.start(SIX_AM_LAGOS);

        EmailMessage email = ReminderEmail.render(user("Tunde"), prayer, "https://app", SIX_AM_LAGOS.plusSeconds(30));

        assertThat(email.subject()).isEqualTo("Happening now: 6am Prayer Room");
        assertThat(email.text()).contains("has started");
    }

    @ParameterizedTest
    @CsvSource({"0, now", "-120, now", "29, now", "31, in 1 minute", "60, in 1 minute", "600, in 10 minutes",
        "5340, in 89 minutes", "5400, in 2 hours", "86400, in 24 hours"})
    void startsInReadsNaturally(long seconds, String expected) {
        assertThat(ReminderEmail.startsIn(Duration.ofSeconds(seconds))).isEqualTo(expected);
    }
}
