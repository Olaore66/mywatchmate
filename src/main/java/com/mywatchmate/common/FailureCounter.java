package com.mywatchmate.common;

import java.time.Duration;

/**
 * Counts failures (e.g. wrong passwords) per key over a sliding window. Once a key has {@code max} failures in
 * the window it's blocked until the oldest of them leaves the window. Only failures count; a success can clear
 * the key. In memory (LOCAL) or shared through Redis (REDIS), like {@link RateLimiters}.
 */
public interface FailureCounter {

    /** How long the key is still blocked; zero if it isn't. */
    Duration blockedFor(String key);

    void failed(String key);

    void cleared(String key);
}
