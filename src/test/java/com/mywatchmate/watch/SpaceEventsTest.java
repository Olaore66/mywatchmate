package com.mywatchmate.watch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.messaging.simp.SimpMessageSendingOperations;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@ExtendWith(MockitoExtension.class)
class SpaceEventsTest {

    @Mock SimpMessageSendingOperations broker;
    @Mock ApplicationEventPublisher internal;

    private final UUID spaceId = UUID.randomUUID();

    @AfterEach
    void clearTransaction() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void outsideATransactionTheEventGoesOutImmediately() {
        new SpaceEvents(broker, internal).publish(spaceId, SpaceEvent.Type.MEMBER_LEFT, Map.of("id", "x"));

        ArgumentCaptor<Object> sent = ArgumentCaptor.forClass(Object.class);
        verify(broker).convertAndSend(eq("/topic/spaces/" + spaceId + "/events"), sent.capture());
        SpaceEvent event = (SpaceEvent) sent.getValue();
        assertThat(event.type()).isEqualTo(SpaceEvent.Type.MEMBER_LEFT);
        assertThat(event.spaceId()).isEqualTo(spaceId);
        assertThat(event.at()).isNotNull();
        assertThat(event.data()).isEqualTo(Map.of("id", "x"));
    }

    @Test
    void insideATransactionTheEventWaitsForTheCommit() {
        TransactionSynchronizationManager.initSynchronization();

        new SpaceEvents(broker, internal).publish(spaceId, SpaceEvent.Type.MEMBER_JOINED, null);
        verify(broker, never()).convertAndSend(any(String.class), any(Object.class));

        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        verify(broker).convertAndSend(eq(SpaceEvents.topic(spaceId)), any(SpaceEvent.class));
    }

    @Test
    void listenersInsideTheAppHearStraightAwayEvenInATransaction() {
        TransactionSynchronizationManager.initSynchronization();

        new SpaceEvents(broker, internal).publish(spaceId, SpaceEvent.Type.MEMBER_JOINED, Map.of("id", "x"));

        ArgumentCaptor<Object> heard = ArgumentCaptor.forClass(Object.class);
        verify(internal).publishEvent(heard.capture());
        assertThat(((SpaceEvent) heard.getValue()).type()).isEqualTo(SpaceEvent.Type.MEMBER_JOINED);
        verify(broker, never()).convertAndSend(any(String.class), any(Object.class));
    }

    @Test
    void aRolledBackTransactionSendsNothing() {
        TransactionSynchronizationManager.initSynchronization();

        new SpaceEvents(broker, internal).publish(spaceId, SpaceEvent.Type.MEMBER_JOINED, null);
        TransactionSynchronizationManager.getSynchronizations()
            .forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

        verify(broker, never()).convertAndSend(any(String.class), any(Object.class));
    }
}
