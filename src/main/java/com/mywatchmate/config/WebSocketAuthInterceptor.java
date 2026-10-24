package com.mywatchmate.config;

import com.mywatchmate.auth.JwtPrincipalConverter;
import com.mywatchmate.auth.PrincipalAuthentication;
import com.mywatchmate.watch.SpaceAccess;
import com.mywatchmate.watch.SpaceEvents;
import java.security.Principal;
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
 * Frames from an unauthenticated session are rejected, and a room's topics
 * ({@code /topic/spaces/{id}/...}) can only be subscribed to by its current members.
 */
@Component
@RequiredArgsConstructor
public class WebSocketAuthInterceptor implements ChannelInterceptor {

    private static final String BEARER = "Bearer ";

    private final JwtDecoder jwtDecoder;
    private final JwtPrincipalConverter converter;
    private final SpaceAccess spaceAccess;

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
        } else if (command == StompCommand.SEND || command == StompCommand.SUBSCRIBE) {
            Principal user = accessor.getUser();
            if (!(user instanceof PrincipalAuthentication auth)) {
                throw new AccessDeniedException("Not authenticated");
            }
            if (command == StompCommand.SUBSCRIBE && !maySubscribe(auth, accessor.getDestination())) {
                throw new AccessDeniedException("Not allowed to subscribe to " + accessor.getDestination());
            }
        }
        return message;
    }

    /** Own queues: always. A room's topics: current members only. Anything else: no. */
    private boolean maySubscribe(PrincipalAuthentication auth, String destination) {
        if (destination == null) {
            return false;
        }
        if (destination.startsWith("/user/")) {
            return true;
        }
        return SpaceEvents.roomOf(destination)
            .map(spaceId -> spaceAccess.isActiveMember(auth.getPrincipal(), spaceId))
            .orElse(false);
    }
}
