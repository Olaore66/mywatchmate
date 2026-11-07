package com.mywatchmate.cluster;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.mywatchmate.common.RateLimiters;
import com.mywatchmate.live.RoomPresence;
import com.mywatchmate.watch.SpaceEvent;
import com.mywatchmate.watch.SpaceEvents;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** The whole app starts in REDIS mode and its room events go through Redis (skipped without Docker). */
@SpringBootTest(properties = "mywatchmate.cluster.mode=redis")
@Testcontainers(disabledWithoutDocker = true)
class RedisModeContextTests {

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void redis(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Autowired ClusterBus bus;
    @Autowired RealtimeBroadcast broadcast;
    @Autowired RateLimiters limits;
    @Autowired RoomPresence presence;
    @Autowired SpaceEvents events;

    @Test
    void theRedisVersionsAreWiredIn() {
        assertThat(bus).isInstanceOf(RedisClusterBus.class);
        assertThat(broadcast).isInstanceOf(RedisRealtimeBroadcast.class);
        assertThat(limits).isInstanceOf(RedisRateLimiters.class);
        assertThat(presence).isInstanceOf(RedisRoomPresence.class);
    }

    @Test
    void aRoomEventGoesOutThroughRedis() {
        List<RedisRealtimeBroadcast.Frame> heard = new CopyOnWriteArrayList<>();
        bus.subscribe(RedisRealtimeBroadcast.CHANNEL, RedisRealtimeBroadcast.Frame.class, heard::add);
        UUID room = UUID.randomUUID();

        // Pub/sub only delivers once the subscription is in place: repeat until it is
        await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(200)).until(() -> {
            events.publish(room, SpaceEvent.Type.TYPING, Map.of("displayName", "Bisi"));
            return heard.stream().anyMatch(f -> f.destination().equals(SpaceEvents.topic(room)));
        });

        RedisRealtimeBroadcast.Frame frame = heard.stream().filter(f -> f.destination().equals(SpaceEvents.topic(room)))
            .findFirst().orElseThrow();
        assertThat(frame.contentType()).startsWith("application/json");
        assertThat(frame.body()).contains("\"type\":\"TYPING\"").contains("\"spaceId\":\"" + room + "\"")
            .contains("\"displayName\":\"Bisi\"");
    }
}
