package com.mywatchmate.live;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;
import org.springframework.web.socket.handler.WebSocketHandlerDecoratorFactory;

/**
 * Keeps hold of open WebSocket connections so the server can close one, e.g. when its person is
 * removed from the room they're watching. A connection's id is also its STOMP session id.
 */
@Slf4j
@Component
public class ConnectionRegistry implements WebSocketHandlerDecoratorFactory {

    /** Sent when someone is no longer in a room they had open. Don't reconnect to that room. */
    public static final CloseStatus NO_LONGER_A_MEMBER = new CloseStatus(4403, "No longer in this room");

    private final Map<String, WebSocketSession> open = new ConcurrentHashMap<>();

    @Override
    public WebSocketHandler decorate(WebSocketHandler handler) {
        return new WebSocketHandlerDecorator(handler) {
            @Override
            public void afterConnectionEstablished(WebSocketSession session) throws Exception {
                open.put(session.getId(), session);
                super.afterConnectionEstablished(session);
            }

            @Override
            public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
                open.remove(session.getId());
                super.afterConnectionClosed(session, status);
            }
        };
    }

    public void close(String connectionId, CloseStatus status) {
        WebSocketSession session = open.get(connectionId);
        if (session == null || !session.isOpen()) {
            return;
        }
        try {
            session.close(status);
        } catch (IOException e) {
            log.debug("Closing WebSocket {} failed: {}", connectionId, e.getMessage());
        }
    }

    public int openCount() {
        return open.size();
    }
}
