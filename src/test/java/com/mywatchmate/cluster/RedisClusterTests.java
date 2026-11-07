package com.mywatchmate.cluster;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.mywatchmate.common.RateLimiter;
import com.mywatchmate.live.PresenceTracker;
import com.mywatchmate.live.dto.OnlineViewer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.json.JsonMapper;

/**
 * REDIS mode against a real Redis (in Docker; skipped when Docker isn't running), with two "copies" of the app
 * talking to it, as two servers behind a load balancer would.
 */
@Testcontainers(disabledWithoutDocker = true)
class RedisClusterTests {

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);

    private static LettuceConnectionFactory connections;
    private static StringRedisTemplate redis;
    private final JsonMapper json = JsonMapper.builder().build();
    private final List<RedisMessageListenerContainer> listeners = new CopyOnWriteArrayList<>();

    @BeforeAll
    static void connect() {
        connections = new LettuceConnectionFactory(new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379)));
        connections.afterPropertiesSet();
        connections.start();
        redis = new StringRedisTemplate(connections);
    }

    @AfterAll
    static void disconnect() {
        connections.destroy();
    }

    @AfterEach
    void stopListeners() throws Exception {
        for (RedisMessageListenerContainer container : listeners) {
            container.destroy();
        }
    }

    /** One copy's bus, as ClusterConfig builds it. */
    private RedisClusterBus bus() {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connections);
        container.setTaskExecutor(Executors.newSingleThreadExecutor());
        container.afterPropertiesSet();
        container.start();
        listeners.add(container);
        return new RedisClusterBus(redis, container, json);
    }

    record Ping(int n, String text) {
    }

    // ---- bus ------------------------------------------------------------------

    @Test
    void everyCopyHearsEveryMessageInOrderItsOwnToo() {
        RedisClusterBus first = bus();
        RedisClusterBus second = bus();
        String channel = "test-" + UUID.randomUUID();
        List<Ping> heardByFirst = new CopyOnWriteArrayList<>();
        List<Ping> heardBySecond = new CopyOnWriteArrayList<>();
        first.subscribe(channel, Ping.class, heardByFirst::add);
        second.subscribe(channel, Ping.class, heardBySecond::add);
        untilListening(() -> first.publish(channel, PROBE), heardByFirst, heardBySecond);

        List<Ping> sent = IntStream.range(0, 50).mapToObj(i -> new Ping(i, "émoji ✝ " + i)).toList();
        sent.forEach(p -> first.publish(channel, p));

        await().atMost(Duration.ofSeconds(5)).until(() -> real(heardByFirst).size() == 50 && real(heardBySecond).size() == 50);
        assertThat(real(heardByFirst)).containsExactlyElementsOf(sent);
        assertThat(real(heardBySecond)).containsExactlyElementsOf(sent);
    }

    @Test
    void aBadMessageIsSkippedAndTheNextOneStillArrives() {
        RedisClusterBus bus = bus();
        String channel = "test-" + UUID.randomUUID();
        List<Ping> heard = new CopyOnWriteArrayList<>();
        bus.subscribe(channel, Ping.class, heard::add);
        untilListening(() -> bus.publish(channel, PROBE), heard);

        redis.convertAndSend(RedisClusterBus.PREFIX + channel, "not json");
        bus.publish(channel, new Ping(1, "ok"));

        await().atMost(Duration.ofSeconds(5)).until(() -> real(heard).size() == 1);
        assertThat(real(heard)).containsExactly(new Ping(1, "ok"));
    }

    // ---- rate limits ----------------------------------------------------------

    @Test
    void aLimitHoldsAcrossCopies() {
        Clock clock = Clock.fixed(Instant.parse("2026-11-02T10:00:00Z"), ZoneOffset.UTC);
        String name = "test-" + UUID.randomUUID();
        RateLimiter onFirst = new RedisRateLimiters(redis, clock).limiter(name, 3, Duration.ofSeconds(10));
        RateLimiter onSecond = new RedisRateLimiters(redis, clock).limiter(name, 3, Duration.ofSeconds(10));
        UUID tunde = UUID.randomUUID();

        assertThat(onFirst.tryAcquire(tunde)).isTrue();
        assertThat(onSecond.tryAcquire(tunde)).isTrue();
        assertThat(onFirst.tryAcquire(tunde)).isTrue();
        assertThat(onSecond.tryAcquire(tunde)).as("the 4th, whichever copy it lands on").isFalse();
        assertThat(onFirst.tryAcquire(UUID.randomUUID())).as("someone else has their own budget").isTrue();
    }

    @Test
    void theWindowSlidesAndRefusedActionsDontCount() {
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-02T10:00:00Z"));
        Clock clock = new Clock() {
            @Override
            public ZoneOffset getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(java.time.ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return now.get();
            }
        };
        RateLimiter limiter = new RedisRateLimiters(redis, clock).limiter("test-" + UUID.randomUUID(), 2, Duration.ofSeconds(1));
        UUID ada = UUID.randomUUID();

        assertThat(limiter.tryAcquire(ada)).isTrue();
        now.set(now.get().plusMillis(500));
        assertThat(limiter.tryAcquire(ada)).isTrue();
        for (int i = 0; i < 10; i++) {
            assertThat(limiter.tryAcquire(ada)).isFalse();
        }
        now.set(now.get().plusMillis(500));
        assertThat(limiter.tryAcquire(ada)).as("the first action left the window").isTrue();
        assertThat(limiter.tryAcquire(ada)).isFalse();
    }

    @Test
    void manyActionsAtOnceNeverGoOverTheLimit() throws Exception {
        RateLimiter limiter = new RedisRateLimiters(redis, Clock.systemUTC()).limiter("test-" + UUID.randomUUID(), 5, Duration.ofMinutes(1));
        UUID bot = UUID.randomUUID();
        ExecutorService pool = Executors.newFixedThreadPool(20);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<Boolean>> tries = IntStream.range(0, 40).mapToObj(i -> pool.submit(() -> {
                go.await();
                return limiter.tryAcquire(bot);
            })).toList();
            go.countDown();
            long allowed = 0;
            for (Future<Boolean> t : tries) {
                allowed += t.get() ? 1 : 0;
            }
            assertThat(allowed).isEqualTo(5);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void withoutRedisActionsAreAllowedRatherThanTheAppFailing() {
        LettuceConnectionFactory nowhere = new LettuceConnectionFactory(new RedisStandaloneConfiguration("localhost", 1));
        nowhere.afterPropertiesSet();
        nowhere.start();
        try {
            RateLimiter limiter = new RedisRateLimiters(new StringRedisTemplate(nowhere), Clock.systemUTC())
                .limiter("down", 1, Duration.ofMinutes(1));
            UUID ada = UUID.randomUUID();

            assertThat(limiter.tryAcquire(ada)).isTrue();
            assertThat(limiter.tryAcquire(ada)).isTrue();
        } finally {
            nowhere.destroy();
        }
    }

    @Test
    void aRefusalSaysHowLongToWait() {
        Clock clock = Clock.fixed(Instant.parse("2026-11-02T10:00:00Z"), ZoneOffset.UTC);
        RateLimiter limiter = new RedisRateLimiters(redis, clock).limiter("test-" + UUID.randomUUID(), 1, Duration.ofMinutes(1));

        assertThat(limiter.acquire("203.0.113.7")).isZero();
        assertThat(limiter.acquire("203.0.113.7")).isEqualTo(Duration.ofMinutes(1));
    }

    // ---- failed sign-ins ------------------------------------------------------

    @Test
    void failuresAreCountedAcrossCopiesAndClearByThemselves() {
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-02T10:00:00Z"));
        Clock clock = new Clock() {
            @Override
            public ZoneOffset getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(java.time.ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return now.get();
            }
        };
        String name = "test-" + UUID.randomUUID();
        com.mywatchmate.common.FailureCounter onFirst = new RedisFailureCounters(redis, clock).counter(name, 3, Duration.ofMinutes(15));
        com.mywatchmate.common.FailureCounter onSecond = new RedisFailureCounters(redis, clock).counter(name, 3, Duration.ofMinutes(15));

        onFirst.failed("ada");
        now.set(now.get().plusSeconds(60));
        onSecond.failed("ada");
        onFirst.failed("ada");
        assertThat(onSecond.blockedFor("bisi")).isZero();
        assertThat(onSecond.blockedFor("ada")).as("until the first failure is 15 minutes old").isEqualTo(Duration.ofMinutes(14));

        // More failures don't make the wait longer than the window
        onFirst.failed("ada");
        onFirst.failed("ada");
        assertThat(onFirst.blockedFor("ada")).isLessThanOrEqualTo(Duration.ofMinutes(15));

        onSecond.cleared("ada");
        assertThat(onFirst.blockedFor("ada")).isZero();

        onFirst.failed("ada");
        onFirst.failed("ada");
        onFirst.failed("ada");
        now.set(now.get().plus(Duration.ofMinutes(15)));
        assertThat(onSecond.blockedFor("ada")).as("cleared by time").isZero();
    }

    @Test
    void withoutRedisSignInStillWorks() {
        LettuceConnectionFactory nowhere = new LettuceConnectionFactory(new RedisStandaloneConfiguration("localhost", 1));
        nowhere.afterPropertiesSet();
        nowhere.start();
        try {
            com.mywatchmate.common.FailureCounter counter = new RedisFailureCounters(new StringRedisTemplate(nowhere), Clock.systemUTC())
                .counter("down", 1, Duration.ofMinutes(1));

            counter.failed("ada");
            counter.cleared("ada");
            assertThat(counter.blockedFor("ada")).isZero();
        } finally {
            nowhere.destroy();
        }
    }

    // ---- presence -------------------------------------------------------------

    private RedisRoomPresence presence(PresenceTracker tracker, String nodeId, Duration ttl) {
        return new RedisRoomPresence(tracker, redis, json, new ClusterProperties(ClusterProperties.Mode.REDIS, nodeId, ttl));
    }

    @Test
    void theRoomsListIsEveryCopysViewersEachOnce() {
        UUID room = UUID.randomUUID();
        PresenceTracker first = new PresenceTracker();
        PresenceTracker second = new PresenceTracker();
        RedisRoomPresence onFirst = presence(first, "app-1", Duration.ofMinutes(1));
        RedisRoomPresence onSecond = presence(second, "app-2", Duration.ofMinutes(1));
        OnlineViewer ada = new OnlineViewer(UUID.randomUUID(), "Ada", false);
        OnlineViewer bisi = new OnlineViewer(UUID.randomUUID(), "Bisi", true);
        try {
            first.subscribed("laptop", "s1", room, ada);
            assertThat(onFirst.localChanged(room)).isTrue();
            second.subscribed("phone", "s1", room, bisi);
            assertThat(onSecond.localChanged(room)).isTrue();
            // Ada's phone on the other copy: she's already online, so the room needn't hear about it
            second.subscribed("ada-phone", "s1", room, ada);
            assertThat(onSecond.localChanged(room)).isFalse();

            assertThat(onFirst.online(room)).containsExactly(ada, bisi);
            assertThat(onSecond.online(room)).containsExactly(ada, bisi);
            assertThat(onSecond.onlineCount(room)).isEqualTo(2);

            // Ada closes her laptop: still online through her phone
            first.disconnected("laptop");
            assertThat(onFirst.localChanged(room)).isFalse();
            assertThat(onFirst.online(room)).containsExactly(ada, bisi);

            // ...and her phone: now she's gone
            second.disconnected("ada-phone");
            assertThat(onSecond.localChanged(room)).isTrue();
            assertThat(onFirst.online(room)).containsExactly(bisi);
        } finally {
            onFirst.leaveAll();
            onSecond.leaveAll();
        }
    }

    @Test
    void aCopyThatCrashesDropsOffWhenItsListExpiresAndALiveOneStaysByRefreshing() {
        UUID room = UUID.randomUUID();
        PresenceTracker crashed = new PresenceTracker();
        PresenceTracker alive = new PresenceTracker();
        Duration ttl = Duration.ofMillis(900);
        RedisRoomPresence onCrashed = presence(crashed, "app-crashed", ttl);
        RedisRoomPresence onAlive = presence(alive, "app-alive", ttl);
        OnlineViewer tunde = new OnlineViewer(UUID.randomUUID(), "Tunde", false);
        OnlineViewer kemi = new OnlineViewer(UUID.randomUUID(), "Kemi", false);
        try {
            crashed.subscribed("c", "s", room, tunde);
            onCrashed.localChanged(room);
            alive.subscribed("c", "s", room, kemi);
            onAlive.localChanged(room);
            // The crashed copy stops refreshing (its process is gone)
            onCrashed.stopRefreshing();

            await().atMost(Duration.ofSeconds(5)).until(() -> onAlive.online(room).equals(List.of(kemi)));
            assertThat(redis.opsForSet().members(RedisRoomPresence.nodesKey(room))).containsExactly("app-alive");
        } finally {
            onCrashed.leaveAll();
            onAlive.leaveAll();
        }
    }

    @Test
    void aCleanShutdownTakesItsViewersOffStraightAway() {
        UUID room = UUID.randomUUID();
        PresenceTracker tracker = new PresenceTracker();
        RedisRoomPresence leaving = presence(tracker, "app-leaving", Duration.ofMinutes(1));
        RedisRoomPresence other = presence(new PresenceTracker(), "app-other", Duration.ofMinutes(1));
        tracker.subscribed("c", "s", room, new OnlineViewer(UUID.randomUUID(), "Femi", false));
        leaving.localChanged(room);

        leaving.leaveAll();

        assertThat(other.online(room)).isEmpty();
        assertThat(redis.hasKey(RedisRoomPresence.nodeKey(room, "app-leaving"))).isFalse();
        other.leaveAll();
    }

    @Test
    void anEmptyRoomHasNobody() {
        RedisRoomPresence presence = presence(new PresenceTracker(), "app-x", Duration.ofMinutes(1));
        try {
            assertThat(presence.online(UUID.randomUUID())).isEmpty();
        } finally {
            presence.leaveAll();
        }
    }

    // ---- live events end to end -------------------------------------------------

    @Test
    void anEventSentOnOneCopyReachesSubscribersOnBoth() {
        RedisRealtimeBroadcastTest.Recording onFirst = new RedisRealtimeBroadcastTest.Recording();
        RedisRealtimeBroadcastTest.Recording onSecond = new RedisRealtimeBroadcastTest.Recording();
        RedisClusterBus firstBus = bus();
        RedisClusterBus secondBus = bus();
        RedisRealtimeBroadcast first = new RedisRealtimeBroadcast(broker(onFirst), firstBus);
        new RedisRealtimeBroadcast(broker(onSecond), secondBus);
        await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(100)).until(() -> {
            first.send("/probe", java.util.Map.of());
            return heardProbe(onFirst) && heardProbe(onSecond);
        });

        first.send("/topic/spaces/x/events", java.util.Map.of("type", "CHAT_MESSAGE"));

        await().atMost(Duration.ofSeconds(5)).until(() -> events(onFirst).size() == 1 && events(onSecond).size() == 1);
        assertThat(new String((byte[]) events(onSecond).getFirst().getPayload())).isEqualTo("{\"type\":\"CHAT_MESSAGE\"}");
    }

    private static org.springframework.messaging.simp.SimpMessagingTemplate broker(RedisRealtimeBroadcastTest.Recording channel) {
        org.springframework.messaging.simp.SimpMessagingTemplate template = new org.springframework.messaging.simp.SimpMessagingTemplate(channel);
        template.setMessageConverter(new org.springframework.messaging.converter.JacksonJsonMessageConverter());
        return template;
    }

    private static final Ping PROBE = new Ping(-1, "probe");

    /** Pub/sub drops what's sent before a subscription is in place: send probes until every copy hears one. */
    @SafeVarargs
    private static void untilListening(Runnable probe, List<Ping>... heard) {
        await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(100)).until(() -> {
            probe.run();
            return java.util.Arrays.stream(heard).allMatch(list -> list.contains(PROBE));
        });
    }

    private static List<Ping> real(List<Ping> heard) {
        return heard.stream().filter(p -> p.n() >= 0).toList();
    }

    private static boolean heardProbe(RedisRealtimeBroadcastTest.Recording channel) {
        return channel.sent.stream().anyMatch(m -> "/probe".equals(
            org.springframework.messaging.simp.SimpMessageHeaderAccessor.getDestination(m.getHeaders())));
    }

    private static List<org.springframework.messaging.Message<?>> events(RedisRealtimeBroadcastTest.Recording channel) {
        return channel.sent.stream().filter(m -> !"/probe".equals(
            org.springframework.messaging.simp.SimpMessageHeaderAccessor.getDestination(m.getHeaders()))).toList();
    }
}
