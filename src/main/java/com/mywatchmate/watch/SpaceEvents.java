package com.mywatchmate.watch;

import java.time.Instant;
import java.util.UUID;
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

    private final SimpMessageSendingOperations broker;
    private final ApplicationEventPublisher internal;

    public static String topic(UUID spaceId) {
        return "/topic/spaces/" + spaceId + "/events";
    }

    public void publish(UUID spaceId, SpaceEvent.Type type, Object data) {
        SpaceEvent event = new SpaceEvent(type, spaceId, Instant.now(), data);
        internal.publishEvent(event);
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
