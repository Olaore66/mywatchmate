package com.mywatchmate.cluster;

import com.mywatchmate.live.PresenceTracker;
import com.mywatchmate.live.RoomPresence;
import com.mywatchmate.live.dto.OnlineViewer;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Several copies of the app: each copy writes its own list of who's online in a room to Redis
 * ({@code ...:{room}:{copy}}, expiring after the TTL) and refreshes it every third of the TTL while it's alive.
 * The room's list is all copies' lists put together. A copy that crashes stops refreshing and drops off by
 * itself; one that shuts down cleanly removes its lists straight away.
 */
@Slf4j
public class RedisRoomPresence implements RoomPresence {

    static final String PREFIX = "mywatchmate:presence:";
    private static final TypeReference<List<Entry>> ENTRIES = new TypeReference<>() {
    };

    /** Someone online through a copy, and when (epoch ms) that copy first saw them. */
    record Entry(OnlineViewer viewer, long since) {
    }

    private final PresenceTracker tracker;
    private final StringRedisTemplate redis;
    private final JsonMapper json;
    private final String nodeId;
    private final Duration ttl;
    private final ScheduledExecutorService refresher;
    private final Clock clock = Clock.systemUTC();
    // Room -> person -> when this copy first saw them come online
    private final Map<UUID, Map<UUID, Long>> firstSeen = new ConcurrentHashMap<>();

    public RedisRoomPresence(PresenceTracker tracker, StringRedisTemplate redis, JsonMapper json, ClusterProperties cluster) {
        this.tracker = tracker;
        this.redis = redis;
        this.json = json;
        this.nodeId = cluster.nodeId();
        this.ttl = cluster.presenceTtl();
        this.refresher = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("presence-refresh").factory());
        long every = Math.max(1, ttl.toMillis() / 3);
        refresher.scheduleAtFixedRate(this::refreshAll, every, every, TimeUnit.MILLISECONDS);
    }

    @Override
    public List<OnlineViewer> online(UUID spaceId) {
        return everyone(spaceId).stream().map(Entry::viewer).toList();
    }

    @Override
    public int onlineCount(UUID spaceId) {
        return everyone(spaceId).size();
    }

    @Override
    public boolean localChanged(UUID spaceId) {
        List<Entry> before = everyone(spaceId);
        Map<UUID, Long> since = new HashMap<>();
        before.forEach(entry -> since.put(entry.viewer().participantId(), entry.since()));
        write(spaceId, since);
        // Who's there, not the order
        return !Set.copyOf(before.stream().map(Entry::viewer).toList()).equals(Set.copyOf(online(spaceId)));
    }

    /** Keeps this copy's lists alive. */
    void refreshAll() {
        try {
            tracker.rooms().forEach(spaceId -> write(spaceId, Map.of()));
        } catch (RuntimeException e) {
            log.warn("Refreshing presence in Redis failed: {}", e.getMessage());
        }
    }

    /** Stops refreshing without removing anything: what a crash looks like from Redis (for tests). */
    void stopRefreshing() {
        refresher.shutdownNow();
    }

    /** A clean shutdown: this copy's viewers go offline now rather than when the lists expire. */
    @PreDestroy
    void leaveAll() {
        refresher.shutdownNow();
        Set<UUID> rooms = new HashSet<>(tracker.rooms());
        rooms.addAll(firstSeen.keySet());
        try {
            for (UUID spaceId : rooms) {
                redis.delete(nodeKey(spaceId, nodeId));
                redis.opsForSet().remove(nodesKey(spaceId), nodeId);
            }
        } catch (RuntimeException e) {
            log.warn("Removing presence from Redis failed: {}", e.getMessage());
        }
        firstSeen.clear();
    }

    /**
     * Every copy's lists put together: each person once, with the earliest time any copy saw them come online,
     * in that order (as the API promises: "in the order they came online").
     */
    private List<Entry> everyone(UUID spaceId) {
        List<String> nodes = new ArrayList<>(members(spaceId));
        if (nodes.isEmpty()) {
            return List.of();
        }
        List<String> lists = redis.opsForValue().multiGet(nodes.stream().map(node -> nodeKey(spaceId, node)).toList());
        Map<UUID, Entry> earliest = new HashMap<>();
        for (int i = 0; i < nodes.size(); i++) {
            String list = lists == null ? null : lists.get(i);
            if (list == null) {
                // That copy's list expired: it's gone
                redis.opsForSet().remove(nodesKey(spaceId), nodes.get(i));
                continue;
            }
            for (Entry entry : json.readValue(list, ENTRIES)) {
                earliest.merge(entry.viewer().participantId(), entry, (a, b) -> a.since() <= b.since() ? a : b);
            }
        }
        return earliest.values().stream()
            .sorted(Comparator.comparingLong(Entry::since).thenComparing(e -> e.viewer().participantId()))
            .toList();
    }

    /**
     * Writes this copy's list for the room. Someone new here who's already online through another copy keeps
     * the time they came online there ({@code knownSince}).
     */
    private synchronized void write(UUID spaceId, Map<UUID, Long> knownSince) {
        List<OnlineViewer> here = tracker.online(spaceId);
        if (here.isEmpty()) {
            firstSeen.remove(spaceId);
            redis.delete(nodeKey(spaceId, nodeId));
            redis.opsForSet().remove(nodesKey(spaceId), nodeId);
            return;
        }
        Map<UUID, Long> seen = firstSeen.computeIfAbsent(spaceId, id -> new ConcurrentHashMap<>());
        seen.keySet().retainAll(here.stream().map(OnlineViewer::participantId).toList());
        long now = clock.millis();
        List<Entry> entries = here.stream()
            .map(viewer -> new Entry(viewer, seen.computeIfAbsent(viewer.participantId(),
                id -> knownSince.getOrDefault(id, now))))
            .toList();
        redis.opsForValue().set(nodeKey(spaceId, nodeId), json.writeValueAsString(entries), ttl);
        redis.opsForSet().add(nodesKey(spaceId), nodeId);
        // The room's set of copies outlives any one copy's list only briefly
        redis.expire(nodesKey(spaceId), ttl.multipliedBy(2));
    }

    private Set<String> members(UUID spaceId) {
        Set<String> nodes = redis.opsForSet().members(nodesKey(spaceId));
        return nodes == null ? Set.of() : nodes;
    }

    static String nodesKey(UUID spaceId) {
        return PREFIX + spaceId + ":nodes";
    }

    static String nodeKey(UUID spaceId, String nodeId) {
        return PREFIX + spaceId + ":" + nodeId;
    }
}
