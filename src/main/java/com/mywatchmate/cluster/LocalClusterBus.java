package com.mywatchmate.cluster;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;

/** One copy of the app: "every copy" is just this one, so messages go straight to the handlers. */
@Slf4j
public class LocalClusterBus implements ClusterBus {

    private final Map<String, List<Consumer<Object>>> handlers = new ConcurrentHashMap<>();

    @Override
    public void publish(String channel, Object message) {
        for (Consumer<Object> handler : handlers.getOrDefault(channel, List.of())) {
            try {
                handler.accept(message);
            } catch (RuntimeException e) {
                // Same as in REDIS mode: one failing handler doesn't stop the others or the publisher
                log.warn("Handler for {} failed: {}", channel, e.getMessage(), e);
            }
        }
    }

    @Override
    public <T> void subscribe(String channel, Class<T> type, Consumer<T> handler) {
        handlers.computeIfAbsent(channel, c -> new CopyOnWriteArrayList<>()).add(message -> handler.accept(type.cast(message)));
    }
}
