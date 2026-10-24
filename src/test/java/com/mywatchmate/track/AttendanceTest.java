package com.mywatchmate.track;

import static com.mywatchmate.testsupport.Fixtures.MONDAY_MIDNIGHT;
import static com.mywatchmate.testsupport.Fixtures.guest;
import static com.mywatchmate.testsupport.Fixtures.session;
import static com.mywatchmate.testsupport.Fixtures.space;
import static com.mywatchmate.testsupport.Fixtures.user;
import static org.assertj.core.api.Assertions.assertThat;

import com.mywatchmate.watch.Space;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AttendanceTest {

    private static final Duration CAP = Duration.ofSeconds(60);

    private Attendance bisi;

    @BeforeEach
    void setUp() {
        Space space = space(user("Ada"));
        bisi = new Attendance(session(space, MONDAY_MIDNIGHT), guest(space, "Bisi"), MONDAY_MIDNIGHT);
    }

    private static Instant at(long seconds) {
        return MONDAY_MIDNIGHT.plusSeconds(seconds);
    }

    @Test
    void firstSeenMeansNoWatchTimeYet() {
        assertThat(bisi.getJoinedAt()).isEqualTo(MONDAY_MIDNIGHT);
        assertThat(bisi.getLastSeenAt()).isEqualTo(MONDAY_MIDNIGHT);
        assertThat(bisi.getWatchedSeconds()).isZero();
        assertThat(bisi.isCounted()).isFalse();
    }

    @Test
    void eachCheckCreditsTheTimeSinceTheLast() {
        bisi.seen(at(30), CAP);
        bisi.seen(at(60), CAP);
        bisi.seen(at(95), CAP);

        assertThat(bisi.getWatchedSeconds()).isEqualTo(95);
        assertThat(bisi.getLastSeenAt()).isEqualTo(at(95));
    }

    @Test
    void aLongGapIsCappedNotCreditedInFull() {
        bisi.seen(at(30), CAP);
        // Away for 10 minutes (or the server paused), then seen again
        bisi.seen(at(630), CAP);

        assertThat(bisi.getWatchedSeconds()).isEqualTo(30 + 60);
    }

    @Test
    void anOutOfOrderOrRepeatedCheckAddsNothing() {
        bisi.seen(at(30), CAP);
        bisi.seen(at(30), CAP);
        bisi.seen(at(10), CAP);

        assertThat(bisi.getWatchedSeconds()).isEqualTo(30);
        assertThat(bisi.getLastSeenAt()).isEqualTo(at(30));
    }

    @Test
    void finishingDecidesWhetherItCounts() {
        bisi.seen(at(60), CAP);
        bisi.finish(60);
        assertThat(bisi.isCounted()).isTrue();
        assertThat(bisi.getLeftAt()).isEqualTo(at(60));

        bisi.finish(61);
        assertThat(bisi.isCounted()).isFalse();
    }

    @Test
    void neverWatchingNeverCountsEvenForAZeroLengthSession() {
        bisi.finish(0);
        assertThat(bisi.isCounted()).isFalse();
    }
}
