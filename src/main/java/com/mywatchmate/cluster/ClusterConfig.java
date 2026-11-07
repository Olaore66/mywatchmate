package com.mywatchmate.cluster;

import com.mywatchmate.common.FailureCounters;
import com.mywatchmate.common.LocalFailureCounters;
import com.mywatchmate.common.LocalRateLimiters;
import com.mywatchmate.common.RateLimiters;
import com.mywatchmate.live.LocalRoomPresence;
import com.mywatchmate.live.PresenceTracker;
import com.mywatchmate.live.RoomPresence;
import java.time.Clock;
import java.util.concurrent.Executors;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.support.TaskExecutorAdapter;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Picks how the copies of the app share live events, presence, rate limits and failed sign-in counts:
 * {@code mywatchmate.cluster.mode} = LOCAL (default; one copy, nothing extra to run) or REDIS (any number
 * of copies behind a load balancer, sharing one Redis). Everything else (endpoints, WebSocket topics, message
 * shapes) is the same in both, so switching never touches the frontend. Background jobs use {@link JobLock}
 * in the database either way.
 */
@Configuration
public class ClusterConfig {

    static final String MODE = "mywatchmate.cluster.mode";

    @Configuration
    @ConditionalOnProperty(name = MODE, havingValue = "local", matchIfMissing = true)
    static class Local {

        @Bean
        ClusterBus clusterBus() {
            return new LocalClusterBus();
        }

        @Bean
        RealtimeBroadcast realtimeBroadcast(SimpMessagingTemplate broker) {
            return new LocalRealtimeBroadcast(broker);
        }

        @Bean
        RateLimiters rateLimiters(Clock clock) {
            return new LocalRateLimiters(clock);
        }

        @Bean
        FailureCounters failureCounters(Clock clock) {
            return new LocalFailureCounters(clock);
        }

        @Bean
        RoomPresence roomPresence(PresenceTracker tracker) {
            return new LocalRoomPresence(tracker);
        }
    }

    @Configuration
    @ConditionalOnProperty(name = MODE, havingValue = "redis")
    static class Redis {

        /** One delivery thread, so messages are handed to the local broker in the order Redis sent them. */
        @Bean
        RedisMessageListenerContainer clusterListeners(RedisConnectionFactory redis) {
            RedisMessageListenerContainer container = new RedisMessageListenerContainer();
            container.setConnectionFactory(redis);
            container.setTaskExecutor(new TaskExecutorAdapter(Executors.newSingleThreadExecutor(
                Thread.ofPlatform().name("cluster-bus").daemon(true).factory())));
            return container;
        }

        @Bean
        ClusterBus clusterBus(StringRedisTemplate redis, RedisMessageListenerContainer clusterListeners, JsonMapper json) {
            return new RedisClusterBus(redis, clusterListeners, json);
        }

        @Bean
        RealtimeBroadcast realtimeBroadcast(SimpMessagingTemplate broker, ClusterBus bus) {
            return new RedisRealtimeBroadcast(broker, bus);
        }

        @Bean
        RateLimiters rateLimiters(StringRedisTemplate redis, Clock clock) {
            return new RedisRateLimiters(redis, clock);
        }

        @Bean
        FailureCounters failureCounters(StringRedisTemplate redis, Clock clock) {
            return new RedisFailureCounters(redis, clock);
        }

        @Bean
        RoomPresence roomPresence(PresenceTracker tracker, StringRedisTemplate redis, JsonMapper json,
                                  ClusterProperties cluster) {
            return new RedisRoomPresence(tracker, redis, json, cluster);
        }
    }
}
