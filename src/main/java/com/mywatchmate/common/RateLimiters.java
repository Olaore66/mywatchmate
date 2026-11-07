package com.mywatchmate.common;

import java.time.Duration;

/**
 * Makes rate limiters. In memory on one copy of the app (LOCAL), or shared through Redis (REDIS) so a limit
 * holds however many copies run. Callers don't know which.
 */
public interface RateLimiters {

    /** @param name what's limited, unique per limiter (it's part of the Redis key) */
    RateLimiter limiter(String name, int max, Duration window);
}
