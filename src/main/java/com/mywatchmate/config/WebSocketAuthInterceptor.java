package com.mywatchmate.config;

import com.mywatchmate.auth.JwtPrincipalConverter;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;

/**
 * Authenticates STOMP connections with the same access token as REST:
 * the client sends {@code Authorization: Bearer <token>} on the CONNECT frame.
 * Frames from an unauthenticated session are rejected.
 */
@Component
@RequiredArgsConstructor
public class WebSocketAuthInterceptor implements ChannelInterceptor {

    private static final String BEARER = "Bearer ";

    private final JwtDecoder jwtDecoder;
    private final JwtPrincipalConverter converter;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || accessor.getCommand() == null) {
            return message;
        }
        StompCommand command = accessor.getCommand();
        if (command == StompCommand.CONNECT) {
            String header = accessor.getFirstNativeHeader("Authorization");
            if (header == null || !header.startsWith(BEARER)) {
                throw new AccessDeniedException("Missing bearer token on CONNECT");
            }
            try {
                accessor.setUser(converter.convert(jwtDecoder.decode(header.substring(BEARER.length()).trim())));
            } catch (JwtException e) {
                throw new AccessDeniedException("Invalid or expired token");
            }
        } else if ((command == StompCommand.SEND || command == StompCommand.SUBSCRIBE) && accessor.getUser() == null) {
            throw new AccessDeniedException("Not authenticated");
        }
        return message;
    }
}
