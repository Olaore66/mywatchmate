package com.mywatchmate.live;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;

@ExtendWith(MockitoExtension.class)
class ConnectionRegistryTest {

    @Mock WebSocketHandler stomp;
    @Mock WebSocketSession session;

    private final ConnectionRegistry registry = new ConnectionRegistry();
    private WebSocketHandler decorated;

    @BeforeEach
    void setUp() {
        decorated = registry.decorate(stomp);
        when(session.getId()).thenReturn("conn-1");
    }

    @Test
    void openConnectionsCanBeClosedByTheServer() throws Exception {
        decorated.afterConnectionEstablished(session);
        verify(stomp).afterConnectionEstablished(session);
        assertThat(registry.openCount()).isEqualTo(1);
        when(session.isOpen()).thenReturn(true);

        registry.close("conn-1", ConnectionRegistry.NO_LONGER_A_MEMBER);

        verify(session).close(ConnectionRegistry.NO_LONGER_A_MEMBER);
        assertThat(ConnectionRegistry.NO_LONGER_A_MEMBER.getCode()).isEqualTo(4403);
    }

    @Test
    void closedConnectionsAreForgotten() throws Exception {
        decorated.afterConnectionEstablished(session);
        decorated.afterConnectionClosed(session, CloseStatus.NORMAL);

        verify(stomp).afterConnectionClosed(session, CloseStatus.NORMAL);
        assertThat(registry.openCount()).isZero();
        registry.close("conn-1", ConnectionRegistry.NO_LONGER_A_MEMBER);
        verify(session, never()).close(any());
    }

    @Test
    void aConnectionAlreadyClosingIsLeftAlone() throws Exception {
        decorated.afterConnectionEstablished(session);
        when(session.isOpen()).thenReturn(false);

        registry.close("conn-1", ConnectionRegistry.NO_LONGER_A_MEMBER);

        verify(session, never()).close(any());
    }

    @Test
    void closingSomethingAlreadyGoneIsHarmless() throws Exception {
        decorated.afterConnectionEstablished(session);
        when(session.isOpen()).thenReturn(true);
        doThrow(new IOException("broken pipe")).when(session).close(any());

        assertThatCode(() -> registry.close("conn-1", ConnectionRegistry.NO_LONGER_A_MEMBER)).doesNotThrowAnyException();
        assertThatCode(() -> registry.close("unknown", ConnectionRegistry.NO_LONGER_A_MEMBER)).doesNotThrowAnyException();
    }
}
