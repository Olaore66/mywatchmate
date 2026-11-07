package com.mywatchmate.cluster;

import java.nio.charset.StandardCharsets;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.util.MimeType;

/**
 * Several copies of the app: the message is converted once, here, with the broker's own converter (so the JSON
 * is exactly what LOCAL mode sends), then fanned out through the cluster bus; each copy's in-memory broker
 * delivers it to the subscribers connected there. Order is kept: one channel, delivered on one thread.
 */
public class RedisRealtimeBroadcast implements RealtimeBroadcast {

    static final String CHANNEL = "realtime";

    /** A ready-to-send WebSocket message. */
    public record Frame(String destination, String contentType, String body) {
    }

    private final SimpMessagingTemplate broker;
    private final ClusterBus bus;

    public RedisRealtimeBroadcast(SimpMessagingTemplate broker, ClusterBus bus) {
        this.broker = broker;
        this.bus = bus;
        bus.subscribe(CHANNEL, Frame.class, this::deliver);
    }

    @Override
    public void send(String destination, Object payload) {
        Message<?> converted = broker.getMessageConverter().toMessage(payload, null);
        if (converted == null) {
            throw new IllegalArgumentException("Can't convert " + payload.getClass().getName() + " for " + destination);
        }
        Object type = converted.getHeaders().get(MessageHeaders.CONTENT_TYPE);
        Object body = converted.getPayload();
        String text = body instanceof byte[] bytes ? new String(bytes, StandardCharsets.UTF_8) : body.toString();
        bus.publish(CHANNEL, new Frame(destination, type == null ? null : type.toString(), text));
    }

    void deliver(Frame frame) {
        SimpMessageHeaderAccessor headers = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        if (frame.contentType() != null) {
            headers.setContentType(MimeType.valueOf(frame.contentType()));
        }
        headers.setLeaveMutable(true);
        broker.send(frame.destination(),
            MessageBuilder.createMessage(frame.body().getBytes(StandardCharsets.UTF_8), headers.getMessageHeaders()));
    }
}
