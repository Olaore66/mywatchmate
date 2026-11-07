package com.mywatchmate.common;

import static com.mywatchmate.testsupport.Fixtures.MONDAY_MIDNIGHT;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class LocalFailureCountersTest {

    private Instant now = MONDAY_MIDNIGHT;
    private final Clock clock = new Clock() {
        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    };

    @Test
    void blockedOnceThereAreEnoughFailuresUntilTheOldestAges() {
        FailureCounter counter = new LocalFailureCounters(clock).counter("x", 2, Duration.ofMinutes(10));

        assertThat(counter.blockedFor("ada")).isZero();
        counter.failed("ada");
        now = now.plus(Duration.ofMinutes(2));
        counter.failed("ada");
        assertThat(counter.blockedFor("ada")).isEqualTo(Duration.ofMinutes(8));

        now = now.plus(Duration.ofMinutes(8));
        assertThat(counter.blockedFor("ada")).isZero();
    }

    @Test
    void clearingForgetsTheFailures() {
        FailureCounter counter = new LocalFailureCounters(clock).counter("x", 1, Duration.ofMinutes(10));
        counter.failed("ada");

        counter.cleared("ada");

        assertThat(counter.blockedFor("ada")).isZero();
    }

    @Test
    void keysWithNothingRecentAreForgottenOnceTheListGetsLong() {
        LocalFailureCounters.Counter counter = (LocalFailureCounters.Counter)
            new LocalFailureCounters(clock).counter("x", 1, Duration.ofMinutes(1));
        for (int i = 0; i <= LocalFailureCounters.CLEANUP_ABOVE; i++) {
            counter.failed("key-" + i);
        }
        now = now.plus(Duration.ofMinutes(2));

        counter.failed("still-busy");

        assertThat(counter.trackedCount()).isEqualTo(1);
        assertThat(counter.blockedFor("still-busy")).isEqualTo(Duration.ofMinutes(1));
    }
}
