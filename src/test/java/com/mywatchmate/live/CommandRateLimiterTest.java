package com.mywatchmate.live;

import static com.mywatchmate.testsupport.Fixtures.MONDAY_MIDNIGHT;
import static com.mywatchmate.testsupport.Fixtures.assertApiError;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.mywatchmate.common.LocalRateLimiters;
import com.mywatchmate.common.SlidingWindowLimiter;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class CommandRateLimiterTest {

    /** A clock the test moves by hand. */
    private static final class ManualClock extends Clock {
        private Instant now = MONDAY_MIDNIGHT;

        void advanceMillis(long ms) {
            now = now.plusMillis(ms);
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
    private final CommandRateLimiter limiter = new CommandRateLimiter(new LocalRateLimiters(clock));
    private final UUID tunde = UUID.randomUUID();

    @Test
    void fivePressesASecondAreFineTheSixthIsRefused() {
        for (int i = 0; i < CommandRateLimiter.MAX_PER_WINDOW; i++) {
            limiter.check(tunde);
            clock.advanceMillis(100);
        }

        assertApiError(() -> limiter.check(tunde), HttpStatus.TOO_MANY_REQUESTS, "PLAYBACK_RATE_LIMITED");
    }

    @Test
    void theBudgetComesBackAsTheSecondPasses() {
        for (int i = 0; i < CommandRateLimiter.MAX_PER_WINDOW; i++) {
            limiter.check(tunde);
        }
        clock.advanceMillis(CommandRateLimiter.WINDOW_MS);

        assertThatCode(() -> limiter.check(tunde)).doesNotThrowAnyException();
    }

    @Test
    void refusedPressesDontCountAgainstYou() {
        for (int i = 0; i < CommandRateLimiter.MAX_PER_WINDOW; i++) {
            limiter.check(tunde);
        }
        for (int i = 0; i < 20; i++) {
            assertApiError(() -> limiter.check(tunde), HttpStatus.TOO_MANY_REQUESTS, "PLAYBACK_RATE_LIMITED");
        }
        clock.advanceMillis(CommandRateLimiter.WINDOW_MS);

        assertThatCode(() -> limiter.check(tunde)).doesNotThrowAnyException();
    }

    @Test
    void activePeopleKeepTheirCountThroughAClean() {
        CommandRateLimiter small = new CommandRateLimiter(new SlidingWindowLimiter(clock, CommandRateLimiter.MAX_PER_WINDOW,
            Duration.ofMillis(CommandRateLimiter.WINDOW_MS), 1));
        for (int i = 0; i < CommandRateLimiter.MAX_PER_WINDOW; i++) {
            small.check(tunde);
        }
        small.check(UUID.randomUUID());
        small.check(UUID.randomUUID());

        assertApiError(() -> small.check(tunde), HttpStatus.TOO_MANY_REQUESTS, "PLAYBACK_RATE_LIMITED");
    }

    @Test
    void everyoneHasTheirOwnBudget() {
        for (int i = 0; i < CommandRateLimiter.MAX_PER_WINDOW; i++) {
            limiter.check(tunde);
        }

        assertThatCode(() -> limiter.check(UUID.randomUUID())).doesNotThrowAnyException();
    }
}
