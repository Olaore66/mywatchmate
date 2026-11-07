package com.mywatchmate.common;

import java.time.Duration;
import java.util.UUID;

/** At most so many actions per time window for each key (a participant, a user, an IP address...). */
public interface RateLimiter {

    /**
     * Counts the action and returns zero, or (not counted, over the limit) how long until it would be allowed.
     */
    Duration acquire(String key);

    /** Counts the action and returns true, or returns false (not counted) when over the limit. */
    default boolean tryAcquire(String key) {
        return acquire(key).isZero();
    }

    default boolean tryAcquire(UUID key) {
        return tryAcquire(key.toString());
    }
}
