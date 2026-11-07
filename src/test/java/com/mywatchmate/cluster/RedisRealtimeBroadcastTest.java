package com.mywatchmate.cluster;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.converter.JacksonJsonMessageConverter;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.support.AbstractMessageChannel;

/**
 * The frontend must not notice the switch to REDIS mode: a broadcast that went through the cluster bus reaches
 * the broker as exactly the bytes, content type and destination LOCAL mode sends.
 */
class RedisRealtimeBroadcastTest {

    record Event(String type, UUID spaceId, Instant at, Object data) {
    }

    /** Collects what the broker would hand to subscribers. */
    static final class Recording extends AbstractMessageChannel {
        final List<Message<?>> sent = new ArrayList<>();

        @Override
        protected boolean sendInternal(Message<?> message, long timeout) {
            sent.add(message);
            return true;
        }
    }

    private static SimpMessagingTemplate broker(Recording channel) {
        SimpMessagingTemplate template = new SimpMessagingTemplate(channel);
        template.setMessageConverter(new JacksonJsonMessageConverter());
        return template;
    }

    @Test
    void subscribersGetTheSameMessageAsInLocalMode() {
        Event event = new Event("PLAYBACK_CHANGED", UUID.randomUUID(), Instant.parse("2026-11-02T10:15:30.123Z"),
            Map.of("positionSeconds", 42.5, "title", "Psalm 23 — \"The Lord\" ✝"));
        String destination = "/topic/spaces/" + event.spaceId() + "/events";

        Recording local = new Recording();
        new LocalRealtimeBroadcast(broker(local)).send(destination, event);
        Recording clustered = new Recording();
        new RedisRealtimeBroadcast(broker(clustered), new LocalClusterBus()).send(destination, event);

        Message<?> expected = local.sent.getFirst();
        Message<?> actual = clustered.sent.getFirst();
        assertThat(new String((byte[]) actual.getPayload(), StandardCharsets.UTF_8))
            .isEqualTo(new String((byte[]) expected.getPayload(), StandardCharsets.UTF_8));
        assertThat(actual.getHeaders().get(MessageHeaders.CONTENT_TYPE).toString())
            .isEqualTo(expected.getHeaders().get(MessageHeaders.CONTENT_TYPE).toString());
        assertThat(SimpMessageHeaderAccessor.getDestination(actual.getHeaders())).isEqualTo(destination);
    }

    @Test
    void messagesArriveInTheOrderTheyWereSent() {
        Recording channel = new Recording();
        RedisRealtimeBroadcast broadcast = new RedisRealtimeBroadcast(broker(channel), new LocalClusterBus());

        for (int i = 0; i < 5; i++) {
            broadcast.send("/topic/t", Map.of("n", i));
        }

        assertThat(channel.sent).extracting(m -> new String((byte[]) m.getPayload(), StandardCharsets.UTF_8))
            .containsExactly("{\"n\":0}", "{\"n\":1}", "{\"n\":2}", "{\"n\":3}", "{\"n\":4}");
    }

    @Test
    void aFrameWithoutAContentTypeIsStillDelivered() {
        Recording channel = new Recording();
        RedisRealtimeBroadcast broadcast = new RedisRealtimeBroadcast(broker(channel), new LocalClusterBus());

        broadcast.deliver(new RedisRealtimeBroadcast.Frame("/topic/t", null, "{}"));

        assertThat(channel.sent).singleElement()
            .satisfies(m -> assertThat(m.getHeaders().get(MessageHeaders.CONTENT_TYPE)).isNull());
    }

    @Test
    void somethingTheConverterCantHandleFailsLoudly() {
        SimpMessagingTemplate template = new SimpMessagingTemplate(new Recording());
        template.setMessageConverter(new org.springframework.messaging.converter.ByteArrayMessageConverter());
        RedisRealtimeBroadcast broadcast = new RedisRealtimeBroadcast(template, new LocalClusterBus());

        assertThatThrownBy(() -> broadcast.send("/topic/t", Map.of("n", 1)))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("/topic/t");
    }
}
