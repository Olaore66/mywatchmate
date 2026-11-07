package com.mywatchmate.common;

import java.time.Clock;
import java.time.Duration;

/** Limits kept in this copy's memory: exact with one copy of the app. */
public class LocalRateLimiters implements RateLimiters {

    static final int CLEANUP_ABOVE = 10_000;

    private final Clock clock;

    public LocalRateLimiters(Clock clock) {
        this.clock = clock;
    }

    @Override
    public RateLimiter limiter(String name, int max, Duration window) {
        return new SlidingWindowLimiter(clock, max, window, CLEANUP_ABOVE);
    }
}
