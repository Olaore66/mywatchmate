package com.mywatchmate.common;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * At most {@code max} actions per {@code window} for each key (usually a participant), counted over a
 * sliding window. In memory, per server: good enough for slowing down floods even with several servers.
 */
public class SlidingWindowLimiter {

    private final Clock clock;
    private final int max;
    private final long windowMs;
    // Past this many keys tracked, forget the ones that haven't acted lately
    private final int cleanupAbove;
    private final Map<UUID, Deque<Long>> recent = new ConcurrentHashMap<>();

    public SlidingWindowLimiter(Clock clock, int max, Duration window, int cleanupAbove) {
        this.clock = clock;
        this.max = max;
        this.windowMs = window.toMillis();
        this.cleanupAbove = cleanupAbove;
    }

    /** Counts the action and returns true, or returns false (not counted) when over the limit. */
    public boolean tryAcquire(UUID key) {
        long now = clock.instant().toEpochMilli();
        if (recent.size() > cleanupAbove) {
            recent.values().removeIf(actions -> isIdle(actions, now));
        }
        Deque<Long> actions = recent.computeIfAbsent(key, id -> new ArrayDeque<>());
        synchronized (actions) {
            while (!actions.isEmpty() && actions.peekFirst() <= now - windowMs) {
                actions.pollFirst();
            }
            if (actions.size() >= max) {
                return false;
            }
            actions.addLast(now);
            return true;
        }
    }

    public int trackedCount() {
        return recent.size();
    }

    private boolean isIdle(Deque<Long> actions, long now) {
        synchronized (actions) {
            return actions.isEmpty() || actions.peekLast() <= now - windowMs;
        }
    }
}
