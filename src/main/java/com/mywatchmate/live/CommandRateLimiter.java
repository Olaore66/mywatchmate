package com.mywatchmate.live;

import com.mywatchmate.common.ApiException;
import com.mywatchmate.common.SlidingWindowLimiter;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * At most {@value #MAX_PER_WINDOW} playback presses per person per second, so one jittery
 * client (or someone mashing the seek bar) can't flood the room with PLAYBACK_CHANGED events.
 */
@Component
public class CommandRateLimiter {

    static final int MAX_PER_WINDOW = 5;
    static final long WINDOW_MS = 1000;
    private static final int CLEANUP_ABOVE = 10_000;

    private final SlidingWindowLimiter limiter;

    @Autowired
    public CommandRateLimiter(Clock clock) {
        this(clock, CLEANUP_ABOVE);
    }

    CommandRateLimiter(Clock clock, int cleanupAbove) {
        this.limiter = new SlidingWindowLimiter(clock, MAX_PER_WINDOW, Duration.ofMillis(WINDOW_MS), cleanupAbove);
    }

    /** Counts this press, or throws 429 PLAYBACK_RATE_LIMITED. */
    public void check(UUID participantId) {
        if (!limiter.tryAcquire(participantId)) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "PLAYBACK_RATE_LIMITED",
                "Too many playback changes at once. Wait a moment and try again.");
        }
    }

    int trackedCount() {
        return limiter.trackedCount();
    }
}
