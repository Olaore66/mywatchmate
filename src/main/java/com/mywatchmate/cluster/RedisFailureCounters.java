package com.mywatchmate.cluster;

import com.mywatchmate.common.FailureCounter;
import com.mywatchmate.common.FailureCounters;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * Failure counts shared by every copy of the app: a sorted set of failure times per key, keeping only the
 * latest {@code max}. If Redis can't be reached nothing is blocked (and the failure isn't counted): sign-in
 * keeps working, and the per-IP limits still apply.
 */
@Slf4j
public class RedisFailureCounters implements FailureCounters {

    static final String PREFIX = "mywatchmate:failures:";

    // KEYS[1]; ARGV: now, window, max. Returns ms still blocked (0 = not blocked).
    static final RedisScript<Long> BLOCKED_FOR = RedisScript.of("""
        local now = tonumber(ARGV[1])
        local window = tonumber(ARGV[2])
        redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', now - window)
        if redis.call('ZCARD', KEYS[1]) < tonumber(ARGV[3]) then
            return 0
        end
        local oldest = redis.call('ZRANGE', KEYS[1], 0, 0, 'WITHSCORES')
        return math.max(1, tonumber(oldest[2]) + window - now)
        """, Long.class);

    // KEYS[1]; ARGV: now, window, max, unique member
    static final RedisScript<Long> FAILED = RedisScript.of("""
        local now = tonumber(ARGV[1])
        local window = tonumber(ARGV[2])
        redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', now - window)
        redis.call('ZADD', KEYS[1], now, ARGV[4])
        redis.call('ZREMRANGEBYRANK', KEYS[1], 0, -(tonumber(ARGV[3]) + 1))
        redis.call('PEXPIRE', KEYS[1], window)
        return 1
        """, Long.class);

    private final StringRedisTemplate redis;
    private final Clock clock;

    public RedisFailureCounters(StringRedisTemplate redis, Clock clock) {
        this.redis = redis;
        this.clock = clock;
    }

    @Override
    public FailureCounter counter(String name, int max, Duration window) {
        String prefix = PREFIX + name + ":";
        String windowMs = Long.toString(window.toMillis());
        String limit = Integer.toString(max);
        return new FailureCounter() {
            @Override
            public Duration blockedFor(String key) {
                try {
                    Long ms = redis.execute(BLOCKED_FOR, List.of(prefix + key), now(), windowMs, limit);
                    return ms == null ? Duration.ZERO : Duration.ofMillis(ms);
                } catch (RuntimeException e) {
                    log.warn("Failure count {} not checked (Redis unavailable): {}", name, e.getMessage());
                    return Duration.ZERO;
                }
            }

            @Override
            public void failed(String key) {
                try {
                    String now = now();
                    redis.execute(FAILED, List.of(prefix + key), now, windowMs, limit, now + ":" + UUID.randomUUID());
                } catch (RuntimeException e) {
                    log.warn("Failure {} not counted (Redis unavailable): {}", name, e.getMessage());
                }
            }

            @Override
            public void cleared(String key) {
                try {
                    redis.delete(prefix + key);
                } catch (RuntimeException e) {
                    log.warn("Failures {} not cleared (Redis unavailable): {}", name, e.getMessage());
                }
            }
        };
    }

    private String now() {
        return Long.toString(clock.millis());
    }
}
