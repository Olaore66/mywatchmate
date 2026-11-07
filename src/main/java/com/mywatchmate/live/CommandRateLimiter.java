package com.mywatchmate.live;

import com.mywatchmate.common.ApiException;
import com.mywatchmate.common.RateLimiter;
import com.mywatchmate.common.RateLimiters;
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

    private final RateLimiter limiter;

    @Autowired
    public CommandRateLimiter(RateLimiters limits) {
        this(limits.limiter("playback", MAX_PER_WINDOW, Duration.ofMillis(WINDOW_MS)));
    }

    CommandRateLimiter(RateLimiter limiter) {
        this.limiter = limiter;
    }

    /** Counts this press, or throws 429 PLAYBACK_RATE_LIMITED. */
    public void check(UUID participantId) {
        if (!limiter.tryAcquire(participantId)) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "PLAYBACK_RATE_LIMITED",
                "Too many playback changes at once. Wait a moment and try again.");
        }
    }
}
