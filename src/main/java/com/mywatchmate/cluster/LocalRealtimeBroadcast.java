package com.mywatchmate.cluster;

import org.springframework.messaging.simp.SimpMessageSendingOperations;

/** One copy of the app: its in-memory broker reaches every subscriber. */
public class LocalRealtimeBroadcast implements RealtimeBroadcast {

    private final SimpMessageSendingOperations broker;

    public LocalRealtimeBroadcast(SimpMessageSendingOperations broker) {
        this.broker = broker;
    }

    @Override
    public void send(String destination, Object payload) {
        broker.convertAndSend(destination, payload);
    }
}
