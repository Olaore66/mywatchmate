package com.mywatchmate.common;

import java.time.Duration;

/** Makes failure counters: in memory with one copy of the app, shared through Redis with several. */
public interface FailureCounters {

    /** @param name what's counted, unique per counter (it's part of the Redis key) */
    FailureCounter counter(String name, int max, Duration window);
}
