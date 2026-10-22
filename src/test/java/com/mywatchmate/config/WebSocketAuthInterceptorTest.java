package com.mywatchmate.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mywatchmate.auth.AuthPrincipal;
import com.mywatchmate.auth.JwtPrincipalConverter;
import com.mywatchmate.auth.PrincipalAuthentication;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

@ExtendWith(MockitoExtension.class)
class WebSocketAuthInterceptorTest {

    @Mock JwtDecoder decoder;

    private WebSocketAuthInterceptor interceptor;

    @BeforeEach
    void setUp() {
        interceptor = new WebSocketAuthInterceptor(decoder, new JwtPrincipalConverter());
    }

    @Test
    void connectWithValidTokenAttachesThePrincipal() {
        UUID userId = UUID.randomUUID();
        when(decoder.decode("good-token")).thenReturn(userJwt(userId));

        Message<?> result = interceptor.preSend(frame(StompCommand.CONNECT, "Bearer   good-token  "), null);

        assertThat(((PrincipalAuthentication) StompHeaderAccessor.wrap(result).getUser()).getPrincipal())
            .isEqualTo(AuthPrincipal.user(userId));
    }

    @Test
    void connectWithRejectedTokenIsDenied() {
        when(decoder.decode("bad-token")).thenThrow(new BadJwtException("signature mismatch"));

        assertThatThrownBy(() -> interceptor.preSend(frame(StompCommand.CONNECT, "Bearer bad-token"), null))
            .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void connectWithoutBearerSchemeIsDeniedWithoutDecoding() {
        assertThatThrownBy(() -> interceptor.preSend(frame(StompCommand.CONNECT, "Basic dXNlcjpwYXNz"), null))
            .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> interceptor.preSend(frame(StompCommand.CONNECT, null), null))
            .isInstanceOf(AccessDeniedException.class);
        verify(decoder, never()).decode(anyString());
    }

    @Test
    void sendAndSubscribeNeedAnAuthenticatedSession() {
        assertThatThrownBy(() -> interceptor.preSend(frame(StompCommand.SEND, null), null))
            .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> interceptor.preSend(frame(StompCommand.SUBSCRIBE, null), null))
            .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void sendFromAnAuthenticatedSessionPassesThrough() {
        Message<byte[]> send = frame(StompCommand.SEND, null);
        StompHeaderAccessor.getAccessor(send, StompHeaderAccessor.class)
            .setUser(new JwtPrincipalConverter().convert(userJwt(UUID.randomUUID())));

        assertThat(interceptor.preSend(send, null)).isSameAs(send);
    }

    @Test
    void framesThatNeedNoAuthPassThrough() {
        Message<byte[]> disconnect = frame(StompCommand.DISCONNECT, null);
        assertThat(interceptor.preSend(disconnect, null)).isSameAs(disconnect);

        Message<String> notStomp = MessageBuilder.withPayload("heartbeat").build();
        assertThat(interceptor.preSend(notStomp, null)).isSameAs(notStomp);
    }

    private static Jwt userJwt(UUID userId) {
        return Jwt.withTokenValue("good-token").header("alg", "HS256").subject(userId.toString()).claim("typ", "user").build();
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
