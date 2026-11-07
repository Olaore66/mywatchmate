package com.mywatchmate.cluster;

/**
 * Sends a message to everyone subscribed to a WebSocket destination, whichever copy of the app they're
 * connected to. LOCAL mode hands it to this copy's broker; REDIS mode turns it into the exact JSON frame
 * first and every copy's broker delivers that, so clients get byte-for-byte the same message either way.
 */
public interface RealtimeBroadcast {

    void send(String destination, Object payload);
}
