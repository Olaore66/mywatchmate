package com.mywatchmate.cluster;

import com.mywatchmate.common.RateLimiter;
import com.mywatchmate.common.RateLimiters;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * Limits shared by every copy of the app: a sliding window per key in a Redis sorted set (one entry per
 * counted action, scored by its time), checked and counted in one atomic script. If Redis can't be reached
 * the action is allowed: a limit is there to slow floods, not to take the app down with Redis.
 */
@Slf4j
public class RedisRateLimiters implements RateLimiters {

    static final String PREFIX = "mywatchmate:limit:";

    // KEYS[1] the key; ARGV: now (ms), window (ms), max, a unique member for this action.
    // Returns 0 when counted, else the ms until the oldest counted action leaves the window.
    static final RedisScript<Long> SLIDING_WINDOW = RedisScript.of("""
        local now = tonumber(ARGV[1])
        local window = tonumber(ARGV[2])
        redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', now - window)
        if redis.call('ZCARD', KEYS[1]) >= tonumber(ARGV[3]) then
            local oldest = redis.call('ZRANGE', KEYS[1], 0, 0, 'WITHSCORES')
            return math.max(1, tonumber(oldest[2]) + window - now)
        end
        redis.call('ZADD', KEYS[1], now, ARGV[4])
        redis.call('PEXPIRE', KEYS[1], window)
        return 0
        """, Long.class);

    private final StringRedisTemplate redis;
    private final Clock clock;

    public RedisRateLimiters(StringRedisTemplate redis, Clock clock) {
        this.redis = redis;
        this.clock = clock;
    }

    @Override
    public RateLimiter limiter(String name, int max, Duration window) {
        String prefix = PREFIX + name + ":";
        String windowMs = Long.toString(window.toMillis());
        String limit = Integer.toString(max);
        return key -> {
            long now = clock.instant().toEpochMilli();
            try {
                Long wait = redis.execute(SLIDING_WINDOW, List.of(prefix + key),
                    Long.toString(now), windowMs, limit, now + ":" + UUID.randomUUID());
                return wait == null ? Duration.ZERO : Duration.ofMillis(wait);
            } catch (RuntimeException e) {
                log.warn("Rate limit {} not checked (Redis unavailable): {}", name, e.getMessage());
                return Duration.ZERO;
            }
        };
    }
}
