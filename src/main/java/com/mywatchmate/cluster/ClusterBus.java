package com.mywatchmate.cluster;

import java.util.function.Consumer;

/**
 * Tells every copy of the app something, this one included: e.g. "close Bisi's connections to this room",
 * when Bisi may be connected to any copy. Messages on one channel arrive in the order they were sent from
 * one copy. Nothing is stored: a copy that's down misses what was said meanwhile.
 * <p>
 * LOCAL mode delivers straight away on the caller's thread; REDIS mode goes through Redis pub/sub.
 * Messages are small records (they're sent as JSON in REDIS mode).
 */
public interface ClusterBus {

    void publish(String channel, Object message);

    <T> void subscribe(String channel, Class<T> type, Consumer<T> handler);
}
