package com.mywatchmate.watch;

import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.simp.SimpMessageSendingOperations;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Publishes room events to WebSocket subscribers. Inside a transaction, the event is sent
 * only after commit: nobody hears "Bisi joined" if saving Bisi failed and rolled back.
 * Build {@code data} before calling (while entities are still loaded).
 */
@Component
@RequiredArgsConstructor
public class SpaceEvents {

    private final SimpMessageSendingOperations broker;

    public static String topic(UUID spaceId) {
        return "/topic/spaces/" + spaceId + "/events";
    }

    public void publish(UUID spaceId, SpaceEvent.Type type, Object data) {
        SpaceEvent event = new SpaceEvent(type, spaceId, Instant.now(), data);
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
