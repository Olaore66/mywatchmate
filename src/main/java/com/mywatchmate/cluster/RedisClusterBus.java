package com.mywatchmate.cluster;

import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import tools.jackson.databind.json.JsonMapper;

/**
 * Several copies of the app: messages go through Redis pub/sub as JSON, and every copy (this one too) hears
 * them. The listener container delivers on one thread, so a channel's messages keep their order.
 */
@Slf4j
public class RedisClusterBus implements ClusterBus {

    static final String PREFIX = "mywatchmate:bus:";

    private final StringRedisTemplate redis;
    private final RedisMessageListenerContainer listeners;
    private final JsonMapper json;

    public RedisClusterBus(StringRedisTemplate redis, RedisMessageListenerContainer listeners, JsonMapper json) {
        this.redis = redis;
        this.listeners = listeners;
        this.json = json;
    }

    @Override
    public void publish(String channel, Object message) {
        redis.convertAndSend(PREFIX + channel, json.writeValueAsString(message));
    }

    @Override
    public <T> void subscribe(String channel, Class<T> type, Consumer<T> handler) {
        listeners.addMessageListener((message, pattern) -> {
            try {
                handler.accept(json.readValue(new String(message.getBody(), StandardCharsets.UTF_8), type));
            } catch (RuntimeException e) {
                log.warn("Handler for {} failed: {}", channel, e.getMessage(), e);
            }
        }, new ChannelTopic(PREFIX + channel));
    }
}
