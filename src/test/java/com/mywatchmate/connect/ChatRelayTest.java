package com.mywatchmate.connect;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.mywatchmate.auth.AuthPrincipal;
import com.mywatchmate.auth.PrincipalAuthentication;
import com.mywatchmate.common.SlidingWindowLimiter;
import com.mywatchmate.connect.dto.ChatSignal;
import com.mywatchmate.watch.Participant;
import com.mywatchmate.watch.SpaceAccess;
import com.mywatchmate.watch.SpaceEvent;
import com.mywatchmate.watch.SpaceEvents;
import com.mywatchmate.watch.dto.ParticipantResponse;
import java.security.Principal;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.oauth2.jwt.Jwt;

@ExtendWith(MockitoExtension.class)
class ChatRelayTest {

    @Mock SpaceAccess access;
    @Mock SpaceEvents events;
    @Mock ChatService chat;
    @Mock SlidingWindowLimiter typing;
    @Mock SlidingWindowLimiter reactions;

    private final UUID room = UUID.randomUUID();
    private final UUID bisiId = UUID.randomUUID();
    private final AuthPrincipal bisi = AuthPrincipal.guest(bisiId, room);
    private ChatRelay relay;

    @BeforeEach
    void setUp() {
        relay = new ChatRelay(access, events, chat, typing, reactions);
        lenient().when(access.activeParticipant(bisi, room)).thenReturn(Optional.of(
            new ParticipantResponse(bisiId, "Bisi", Participant.Role.MEMBER, true, Instant.now())));
        lenient().when(typing.tryAcquire(any())).thenReturn(true);
        lenient().when(reactions.tryAcquire(any())).thenReturn(true);
    }

    private static Principal auth(AuthPrincipal who) {
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "HS256").subject("someone").build();
        return new PrincipalAuthentication(who, jwt);
    }

    @Test
    void typingIsPassedOnToTheRoom() {
        relay.typing(room, auth(bisi));

        verify(events).publish(room, SpaceEvent.Type.TYPING, new ChatSignal(bisiId, "Bisi", null));
    }

    @Test
    void reactionsFromTheFixedSetArePassedOn() {
        relay.reaction(room, Map.of("emoji", "🙏"), auth(bisi));

        verify(events).publish(room, SpaceEvent.Type.REACTION, new ChatSignal(bisiId, "Bisi", "🙏"));
    }

    @Test
    void otherEmojiOrNoneAreDropped() {
        Map<String, Object> missing = new HashMap<>();
        missing.put("emoji", null);
        relay.reaction(room, Map.of("emoji", "💩"), auth(bisi));
        relay.reaction(room, Map.of("emoji", 42), auth(bisi));
        relay.reaction(room, missing, auth(bisi));
        relay.reaction(room, null, auth(bisi));

        verifyNoInteractions(events, access);
    }

    @Test
    void tooManyAreQuietlyDropped() {
        when(typing.tryAcquire(bisiId)).thenReturn(false);
        when(reactions.tryAcquire(bisiId)).thenReturn(false);

        relay.typing(room, auth(bisi));
        relay.reaction(room, Map.of("emoji", "🔥"), auth(bisi));

        verifyNoInteractions(events);
    }

    @Test
    void strangersMutedPeopleAndAnonymousConnectionsAreIgnored() {
        AuthPrincipal stranger = AuthPrincipal.user(UUID.randomUUID());
        when(access.activeParticipant(stranger, room)).thenReturn(Optional.empty());
        relay.typing(room, auth(stranger));

        when(chat.isMuted(bisiId)).thenReturn(true);
        relay.typing(room, auth(bisi));
        relay.reaction(room, Map.of("emoji", "🙏"), auth(bisi));

        relay.typing(room, null);

        verifyNoInteractions(events);
    }
}
