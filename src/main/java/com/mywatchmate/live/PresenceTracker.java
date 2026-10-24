package com.mywatchmate.live;

import com.mywatchmate.live.dto.OnlineViewer;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Who has each room open right now. Someone is online in a room while at least one of their
 * WebSocket connections is subscribed to one of its topics, so two tabs or a phone and a laptop
 * count once, and closing one of them keeps them online.
 * <p>
 * In memory: fine on one server, and a restart drops every connection anyway (clients reconnect
 * and subscribe again). With several servers this moves to Redis behind the same methods.
 */
@Component
public class PresenceTracker {

    private record Subscription(UUID spaceId, OnlineViewer viewer) {
    }

    // connection (STOMP session) id → its subscriptions to room topics, by subscription id
    private final Map<String, Map<String, Subscription>> connections = new HashMap<>();
    // room → who is online, in the order they came online
    private final Map<UUID, LinkedHashMap<UUID, OnlineViewer>> rooms = new HashMap<>();

    /** @return true when this made the viewer come online in that room */
    public synchronized boolean subscribed(String connectionId, String subscriptionId, UUID spaceId, OnlineViewer viewer) {
        boolean wasOnline = isOnline(spaceId, viewer.participantId());
        connections.computeIfAbsent(connectionId, id -> new HashMap<>())
            .put(subscriptionId, new Subscription(spaceId, viewer));
        rooms.computeIfAbsent(spaceId, id -> new LinkedHashMap<>()).putIfAbsent(viewer.participantId(), viewer);
        return !wasOnline;
    }

    /** @return the room someone went offline in, or null */
    public synchronized UUID unsubscribed(String connectionId, String subscriptionId) {
        Map<String, Subscription> subscriptions = connections.get(connectionId);
        Subscription gone = subscriptions == null ? null : subscriptions.remove(subscriptionId);
        if (gone == null) {
            return null;
        }
        if (subscriptions.isEmpty()) {
            connections.remove(connectionId);
        }
        return refresh(gone) ? gone.spaceId() : null;
    }

    /** @return the rooms someone went offline in */
    public synchronized Set<UUID> disconnected(String connectionId) {
        Map<String, Subscription> subscriptions = connections.remove(connectionId);
        Set<UUID> changed = new LinkedHashSet<>();
        if (subscriptions != null) {
            for (Subscription gone : subscriptions.values()) {
                if (refresh(gone)) {
                    changed.add(gone.spaceId());
                }
            }
        }
        return changed;
    }

    public synchronized List<OnlineViewer> online(UUID spaceId) {
        Map<UUID, OnlineViewer> viewers = rooms.get(spaceId);
        return viewers == null ? List.of() : List.copyOf(viewers.values());
    }

    public synchronized int onlineCount(UUID spaceId) {
        Map<UUID, OnlineViewer> viewers = rooms.get(spaceId);
        return viewers == null ? 0 : viewers.size();
    }

    /** The connections someone has open on a room, e.g. to close them when they're removed. */
    public synchronized Set<String> connectionsOf(UUID spaceId, UUID participantId) {
        Set<String> found = new HashSet<>();
        connections.forEach((connectionId, subscriptions) -> {
            for (Subscription s : subscriptions.values()) {
                if (s.spaceId().equals(spaceId) && s.viewer().participantId().equals(participantId)) {
                    found.add(connectionId);
                }
            }
        });
        return found;
    }

    private boolean isOnline(UUID spaceId, UUID participantId) {
        Map<UUID, OnlineViewer> viewers = rooms.get(spaceId);
        return viewers != null && viewers.containsKey(participantId);
    }

    /** After a subscription went away: is its viewer still there through another one? Returns true if they left. */
    private boolean refresh(Subscription gone) {
        UUID participantId = gone.viewer().participantId();
        boolean stillThere = connections.values().stream()
            .flatMap(subscriptions -> subscriptions.values().stream())
            .anyMatch(s -> s.spaceId().equals(gone.spaceId()) && s.viewer().participantId().equals(participantId));
        if (stillThere) {
            return false;
        }
        Map<UUID, OnlineViewer> viewers = rooms.get(gone.spaceId());
        if (viewers != null) {
            viewers.remove(participantId);
            if (viewers.isEmpty()) {
                rooms.remove(gone.spaceId());
            }
        }
        return true;
    }
}
