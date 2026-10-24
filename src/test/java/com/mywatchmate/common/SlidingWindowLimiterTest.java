package com.mywatchmate.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SlidingWindowLimiterTest {

    /** A clock the test moves by hand. */
    private static final class ManualClock extends Clock {
        private Instant now = Instant.parse("2026-11-02T00:00:00Z");

        void advance(Duration d) {
            now = now.plus(d);
        }

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
    }

    private final ManualClock clock = new ManualClock();
    private final UUID bisi = UUID.randomUUID();

    @Test
    void upToTheLimitPerWindowThenNo() {
        SlidingWindowLimiter limiter = new SlidingWindowLimiter(clock, 3, Duration.ofSeconds(5), 100);

        assertThat(limiter.tryAcquire(bisi)).isTrue();
        assertThat(limiter.tryAcquire(bisi)).isTrue();
        assertThat(limiter.tryAcquire(bisi)).isTrue();
        assertThat(limiter.tryAcquire(bisi)).isFalse();
    }

    @Test
    void theWindowSlides() {
        SlidingWindowLimiter limiter = new SlidingWindowLimiter(clock, 2, Duration.ofSeconds(5), 100);
        limiter.tryAcquire(bisi);
        clock.advance(Duration.ofSeconds(3));
        limiter.tryAcquire(bisi);

        clock.advance(Duration.ofSeconds(2));
        assertThat(limiter.tryAcquire(bisi)).as("the first one has slid out").isTrue();
        assertThat(limiter.tryAcquire(bisi)).as("the second hasn't yet").isFalse();
    }

    @Test
    void refusalsDontCountAndEveryoneHasTheirOwnAllowance() {
        SlidingWindowLimiter limiter = new SlidingWindowLimiter(clock, 1, Duration.ofSeconds(2), 100);
        limiter.tryAcquire(bisi);
        for (int i = 0; i < 10; i++) {
            limiter.tryAcquire(bisi);
        }
        assertThat(limiter.tryAcquire(UUID.randomUUID())).isTrue();

        clock.advance(Duration.ofSeconds(2));
        assertThat(limiter.tryAcquire(bisi)).isTrue();
    }

    @Test
    void idleKeysAreForgottenOnceManyAreTracked() {
        SlidingWindowLimiter limiter = new SlidingWindowLimiter(clock, 1, Duration.ofSeconds(1), 2);
        for (int i = 0; i < 3; i++) {
            limiter.tryAcquire(UUID.randomUUID());
        }
        assertThat(limiter.trackedCount()).isEqualTo(3);

        clock.advance(Duration.ofSeconds(1));
        limiter.tryAcquire(bisi);

        assertThat(limiter.trackedCount()).isEqualTo(1);
    }
}
