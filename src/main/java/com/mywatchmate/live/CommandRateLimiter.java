package com.mywatchmate.live;

import com.mywatchmate.common.ApiException;
import java.time.Clock;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * At most {@value #MAX_PER_WINDOW} playback presses per person per second, so one jittery
 * client (or someone mashing the seek bar) can't flood the room with PLAYBACK_CHANGED events.
 * In memory, per server: good enough for its purpose even with several servers.
 */
@Component
public class CommandRateLimiter {

    static final int MAX_PER_WINDOW = 5;
    static final long WINDOW_MS = 1000;
    private static final int CLEANUP_ABOVE = 10_000;

    private final Clock clock;
    // Past this many people tracked, forget the ones who haven't pressed anything lately
    private final int cleanupAbove;
    private final Map<UUID, Deque<Long>> recent = new ConcurrentHashMap<>();

    @Autowired
    public CommandRateLimiter(Clock clock) {
        this(clock, CLEANUP_ABOVE);
    }

    CommandRateLimiter(Clock clock, int cleanupAbove) {
        this.clock = clock;
        this.cleanupAbove = cleanupAbove;
    }

    /** Counts this press, or throws 429 PLAYBACK_RATE_LIMITED. */
    public void check(UUID participantId) {
        long now = clock.instant().toEpochMilli();
        if (recent.size() > cleanupAbove) {
            recent.values().removeIf(presses -> isIdle(presses, now));
        }
        Deque<Long> presses = recent.computeIfAbsent(participantId, id -> new ArrayDeque<>());
        synchronized (presses) {
            while (!presses.isEmpty() && presses.peekFirst() <= now - WINDOW_MS) {
                presses.pollFirst();
            }
            if (presses.size() >= MAX_PER_WINDOW) {
                throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "PLAYBACK_RATE_LIMITED",
                    "Too many playback changes at once. Wait a moment and try again.");
            }
            presses.addLast(now);
        }
    }

    int trackedCount() {
        return recent.size();
    }

    private static boolean isIdle(Deque<Long> presses, long now) {
        synchronized (presses) {
            return presses.isEmpty() || presses.peekLast() <= now - WINDOW_MS;
        }
    }
}
