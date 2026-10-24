package com.mywatchmate.watch;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.messaging.simp.SimpMessageSendingOperations;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Publishes room events. Two audiences:
 * <ul>
 *   <li>WebSocket subscribers on {@code /topic/spaces/{id}/events}: inside a transaction, only after
 *       commit, so nobody hears "Bisi joined" if saving Bisi failed and rolled back.</li>
 *   <li>Listeners inside the app ({@code @EventListener SpaceEvent}): straight away, in the same
 *       transaction, so their database work commits or rolls back together with the change.</li>
 * </ul>
 * Build {@code data} before calling (while entities are still loaded).
 */
@Component
@RequiredArgsConstructor
public class SpaceEvents {

    private static final Pattern ROOM_TOPIC =
        Pattern.compile("^/topic/spaces/([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})(/.*)?$");

    private final SimpMessageSendingOperations broker;
    private final ApplicationEventPublisher internal;

    public static String topic(UUID spaceId) {
        return "/topic/spaces/" + spaceId + "/events";
    }

    /** The room a destination belongs to ({@code /topic/spaces/{id}/...}), if any. */
    public static Optional<UUID> roomOf(String destination) {
        if (destination == null) {
            return Optional.empty();
        }
        Matcher room = ROOM_TOPIC.matcher(destination);
        return room.matches() ? Optional.of(UUID.fromString(room.group(1))) : Optional.empty();
    }

    /**
     * Listeners may publish follow-up events (NOW_PLAYING_CHANGED → PLAYBACK_CHANGED); this event's broadcast
     * is queued before they run, so subscribers hear the cause before its effects.
     */
    public void publish(UUID spaceId, SpaceEvent.Type type, Object data) {
        SpaceEvent event = new SpaceEvent(type, spaceId, Instant.now(), data);
        broadcast(spaceId, event);
        internal.publishEvent(event);
    }

    private void broadcast(UUID spaceId, SpaceEvent event) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    broker.convertAndSend(topic(spaceId), event);
                }
            });
        } else {
            broker.convertAndSend(topic(spaceId), event);
        }
    }
}
