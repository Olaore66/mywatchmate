package com.mywatchmate.connect;

import com.mywatchmate.auth.PrincipalAuthentication;
import com.mywatchmate.common.SlidingWindowLimiter;
import com.mywatchmate.connect.dto.ChatSignal;
import com.mywatchmate.watch.SpaceAccess;
import com.mywatchmate.watch.SpaceEvent;
import com.mywatchmate.watch.SpaceEvents;
import com.mywatchmate.watch.dto.ParticipantResponse;
import java.security.Principal;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Controller;

/**
 * "Bisi is typing…" and floating reactions: sent straight over the WebSocket, passed on to the room,
 * never stored. Too many, an unknown emoji, or someone not in the room (or muted) are quietly dropped;
 * they're throwaway signals, not worth an error.
 */
@Controller
public class ChatRelay {

    /** A small fixed set keeps reactions tasteful (church rooms too). */
    public static final Set<String> REACTIONS = Set.of("🙏", "❤️", "🔥", "😂", "👏", "😮");

    private final SpaceAccess access;
    private final SpaceEvents events;
    private final ChatService chat;
    // At most one "typing" per person every 2 s, and 5 reactions every 3 s
    private final SlidingWindowLimiter typing;
    private final SlidingWindowLimiter reactions;

    @Autowired
    public ChatRelay(SpaceAccess access, SpaceEvents events, ChatService chat, Clock clock) {
        this(access, events, chat, new SlidingWindowLimiter(clock, 1, Duration.ofSeconds(2), 10_000),
            new SlidingWindowLimiter(clock, 5, Duration.ofSeconds(3), 10_000));
    }

    ChatRelay(SpaceAccess access, SpaceEvents events, ChatService chat,
              SlidingWindowLimiter typing, SlidingWindowLimiter reactions) {
        this.access = access;
        this.events = events;
        this.chat = chat;
        this.typing = typing;
        this.reactions = reactions;
    }

    @MessageMapping("/spaces/{spaceId}/typing")
    public void typing(@DestinationVariable UUID spaceId, Principal principal) {
        sender(principal, spaceId)
            .filter(me -> typing.tryAcquire(me.id()))
            .ifPresent(me -> events.publish(spaceId, SpaceEvent.Type.TYPING, new ChatSignal(me.id(), me.displayName(), null)));
    }

    @MessageMapping("/spaces/{spaceId}/reaction")
    public void reaction(@DestinationVariable UUID spaceId, @Payload Map<String, Object> body, Principal principal) {
        Object emoji = body == null ? null : body.get("emoji");
        if (!(emoji instanceof String chosen) || !REACTIONS.contains(chosen)) {
            return;
        }
        sender(principal, spaceId)
            .filter(me -> reactions.tryAcquire(me.id()))
            .ifPresent(me -> events.publish(spaceId, SpaceEvent.Type.REACTION, new ChatSignal(me.id(), me.displayName(), chosen)));
    }

    /** Who sent it, if they're in the room and not muted. */
    private Optional<ParticipantResponse> sender(Principal principal, UUID spaceId) {
        if (!(principal instanceof PrincipalAuthentication auth)) {
            return Optional.empty();
        }
        return access.activeParticipant(auth.getPrincipal(), spaceId).filter(me -> !chat.isMuted(me.id()));
    }
}
