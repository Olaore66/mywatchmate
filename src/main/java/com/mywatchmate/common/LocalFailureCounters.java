package com.mywatchmate.common;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Failure counts kept in this copy's memory: exact with one copy of the app. */
public class LocalFailureCounters implements FailureCounters {

    static final int CLEANUP_ABOVE = 10_000;

    private final Clock clock;

    public LocalFailureCounters(Clock clock) {
        this.clock = clock;
    }

    @Override
    public FailureCounter counter(String name, int max, Duration window) {
        return new Counter(clock, max, window.toMillis());
    }

    static final class Counter implements FailureCounter {

        private final Clock clock;
        private final int max;
        private final long windowMs;
        // The latest failures per key (at most max: older ones can't matter)
        private final Map<String, Deque<Long>> failures = new ConcurrentHashMap<>();

        Counter(Clock clock, int max, long windowMs) {
            this.clock = clock;
            this.max = max;
            this.windowMs = windowMs;
        }

        @Override
        public Duration blockedFor(String key) {
            Deque<Long> times = failures.get(key);
            if (times == null) {
                return Duration.ZERO;
            }
            long now = clock.millis();
            synchronized (times) {
                expire(times, now);
                return times.size() >= max ? Duration.ofMillis(Math.max(1, times.peekFirst() + windowMs - now)) : Duration.ZERO;
            }
        }

        @Override
        public void failed(String key) {
            long now = clock.millis();
            if (failures.size() > CLEANUP_ABOVE) {
                failures.values().removeIf(times -> {
                    synchronized (times) {
                        expire(times, now);
                        return times.isEmpty();
                    }
                });
            }
            Deque<Long> times = failures.computeIfAbsent(key, k -> new ArrayDeque<>());
            synchronized (times) {
                expire(times, now);
                times.addLast(now);
                while (times.size() > max) {
                    times.pollFirst();
                }
            }
        }

        @Override
        public void cleared(String key) {
            failures.remove(key);
        }

        int trackedCount() {
            return failures.size();
        }

        private void expire(Deque<Long> times, long now) {
            while (!times.isEmpty() && times.peekFirst() <= now - windowMs) {
                times.pollFirst();
            }
        }
    }
}
