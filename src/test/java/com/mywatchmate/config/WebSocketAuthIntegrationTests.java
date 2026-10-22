package com.mywatchmate.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mywatchmate.auth.AuthPrincipal;
import com.mywatchmate.auth.PrincipalAuthentication;
import com.mywatchmate.auth.TokenService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.access.AccessDeniedException;

@SpringBootTest
class WebSocketAuthIntegrationTests {

    @Autowired WebSocketAuthInterceptor interceptor;
    @Autowired TokenService tokens;

    @Test
    void connectWithValidTokenSetsThePrincipal() {
        UUID userId = UUID.randomUUID();
        Message<?> result = interceptor.preSend(frame(StompCommand.CONNECT, "Bearer " + tokens.userAccessToken(userId)), null);

        assertThat(StompHeaderAccessor.wrap(result).getUser())
            .isInstanceOfSatisfying(PrincipalAuthentication.class,
                auth -> assertThat(auth.getPrincipal()).isEqualTo(AuthPrincipal.user(userId)));
    }

    @Test
    void guestTokensWorkToo() {
        UUID participantId = UUID.randomUUID();
        UUID spaceId = UUID.randomUUID();
        Message<?> result = interceptor.preSend(
            frame(StompCommand.CONNECT, "Bearer " + tokens.guestAccessToken(participantId, spaceId)), null);

        assertThat(((PrincipalAuthentication) StompHeaderAccessor.wrap(result).getUser()).getPrincipal())
            .isEqualTo(AuthPrincipal.guest(participantId, spaceId));
    }

    @Test
    void connectWithoutOrWithBadTokenIsRejected() {
        assertThatThrownBy(() -> interceptor.preSend(frame(StompCommand.CONNECT, null), null))
            .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> interceptor.preSend(frame(StompCommand.CONNECT, "Bearer not.a.jwt"), null))
            .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void sendOrSubscribeWithoutAuthenticatedSessionIsRejected() {
        assertThatThrownBy(() -> interceptor.preSend(frame(StompCommand.SUBSCRIBE, null), null))
            .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> interceptor.preSend(frame(StompCommand.SEND, null), null))
            .isInstanceOf(AccessDeniedException.class);
    }

    private static Message<byte[]> frame(StompCommand command, String authorization) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
        if (authorization != null) {
            accessor.setNativeHeader("Authorization", authorization);
        }
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }
}
