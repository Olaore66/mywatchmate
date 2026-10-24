package com.mywatchmate.config;

import com.mywatchmate.auth.JwtPrincipalConverter;
import com.mywatchmate.auth.PrincipalAuthentication;
import com.mywatchmate.watch.SpaceAccess;
import com.mywatchmate.watch.SpaceEvents;
import java.security.Principal;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
 * Frames from an unauthenticated session are rejected, a room's topics
 * ({@code /topic/spaces/{id}/...}) can only be subscribed to by its current members, and the only
 * frames clients may SEND are typing and reactions to a room they're in.
 */
@Component
@RequiredArgsConstructor
public class WebSocketAuthInterceptor implements ChannelInterceptor {

    private static final String BEARER = "Bearer ";
    // What clients may SEND: typing and reactions, to a room they're in
    private static final Pattern ROOM_ACTION = Pattern.compile(
        "^/app/spaces/([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})/(typing|reaction)$");

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
            if (command == StompCommand.SEND && !maySend(auth, accessor.getDestination())) {
                throw new AccessDeniedException("Not allowed to send to " + accessor.getDestination());
            }
        }
        return message;
    }

    /** Typing and reactions to a room the sender is currently in; nothing else. */
    private boolean maySend(PrincipalAuthentication auth, String destination) {
        if (destination == null) {
            return false;
        }
        Matcher action = ROOM_ACTION.matcher(destination);
        return action.matches() && spaceAccess.isActiveMember(auth.getPrincipal(), UUID.fromString(action.group(1)));
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
