package com.mywatchmate.live;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.mywatchmate.auth.AuthPrincipal;
import com.mywatchmate.auth.PrincipalAuthentication;
import com.mywatchmate.live.dto.OnlineViewer;
import com.mywatchmate.live.dto.PresenceResponse;
import com.mywatchmate.watch.Participant;
import com.mywatchmate.watch.SpaceAccess;
import com.mywatchmate.watch.SpaceEvent;
import com.mywatchmate.watch.SpaceEvents;
import com.mywatchmate.watch.dto.ParticipantResponse;
import java.security.Principal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;
import org.springframework.web.socket.messaging.SessionSubscribeEvent;
import org.springframework.web.socket.messaging.SessionUnsubscribeEvent;

@ExtendWith(MockitoExtension.class)
class PresenceListenerTest {

    @Mock SpaceAccess access;
    @Mock SpaceEvents events;
    @Mock ConnectionRegistry connections;

    private final PresenceTracker presence = new PresenceTracker();
    private final UUID room = UUID.randomUUID();
    private final UUID bisiId = UUID.randomUUID();
    private final AuthPrincipal bisi = AuthPrincipal.guest(bisiId, room);
    private PresenceListener listener;

    @BeforeEach
    void setUp() {
        listener = new PresenceListener(presence, access, events, connections);
    }

    private void bisiIsAMember() {
        when(access.activeParticipant(bisi, room)).thenReturn(Optional.of(
            new ParticipantResponse(bisiId, "Bisi", Participant.Role.MEMBER, true, Instant.now())));
    }

    @Test
    void subscribingToTheRoomPutsThemOnlineAndTellsTheRoom() {
        bisiIsAMember();

        listener.onSubscribe(subscribe("conn-1", "sub-1", "/topic/spaces/" + room + "/events", bisi));

        assertThat(presence.online(room)).containsExactly(new OnlineViewer(bisiId, "Bisi", true));
        ArgumentCaptor<Object> data = ArgumentCaptor.forClass(Object.class);
        verify(events).publish(eq(room), eq(SpaceEvent.Type.PRESENCE_CHANGED), data.capture());
        PresenceResponse told = (PresenceResponse) data.getValue();
        assertThat(told.onlineCount()).isEqualTo(1);
        assertThat(told.online().getFirst().displayName()).isEqualTo("Bisi");
    }

    @Test
    void aSecondTabDoesntAnnounceAgain() {
        bisiIsAMember();
        listener.onSubscribe(subscribe("phone", "sub-1", "/topic/spaces/" + room + "/events", bisi));
        listener.onSubscribe(subscribe("laptop", "sub-1", "/topic/spaces/" + room + "/events", bisi));

        verify(events).publish(eq(room), eq(SpaceEvent.Type.PRESENCE_CHANGED), any());
    }

    @Test
    void otherDestinationsAndStrangersAreIgnored() {
        listener.onSubscribe(subscribe("conn-1", "sub-1", "/user/queue/errors", bisi));
        listener.onSubscribe(subscribe("conn-1", "sub-2", "/topic/spaces/" + room + "/events", null));
        when(access.activeParticipant(bisi, room)).thenReturn(Optional.empty());
        listener.onSubscribe(subscribe("conn-1", "sub-3", "/topic/spaces/" + room + "/events", bisi));

        assertThat(presence.online(room)).isEmpty();
        verifyNoInteractions(events);
    }

    @Test
    void unsubscribingOrDisconnectingTakesThemOffline() {
        bisiIsAMember();
        listener.onSubscribe(subscribe("conn-1", "sub-1", "/topic/spaces/" + room + "/events", bisi));
        listener.onUnsubscribe(new SessionUnsubscribeEvent(this, frame(StompCommand.UNSUBSCRIBE, "conn-1", "sub-1", null), auth(bisi)));
        assertThat(presence.online(room)).isEmpty();

        listener.onSubscribe(subscribe("conn-2", "sub-1", "/topic/spaces/" + room + "/events", bisi));
        listener.onDisconnect(new SessionDisconnectEvent(this, frame(StompCommand.DISCONNECT, "conn-2", null, null),
            "conn-2", CloseStatus.GOING_AWAY, auth(bisi)));
        assertThat(presence.online(room)).isEmpty();

        // came, went, came, went
        verify(events, times(4)).publish(eq(room), eq(SpaceEvent.Type.PRESENCE_CHANGED), any());
    }

    @Test
    void someoneRemovedHasTheirConnectionsToThatRoomClosed() {
        bisiIsAMember();
        listener.onSubscribe(subscribe("phone", "sub-1", "/topic/spaces/" + room + "/events", bisi));
        listener.onSubscribe(subscribe("laptop", "sub-1", "/topic/spaces/" + room + "/events", bisi));

        listener.onSpaceEvent(new SpaceEvent(SpaceEvent.Type.MEMBER_REMOVED, room, Instant.now(),
            new ParticipantResponse(bisiId, "Bisi", Participant.Role.MEMBER, true, Instant.now())));

        verify(connections).close("phone", ConnectionRegistry.NO_LONGER_A_MEMBER);
        verify(connections).close("laptop", ConnectionRegistry.NO_LONGER_A_MEMBER);
    }

    @Test
    void someoneLeavingHasTheirConnectionsClosedToo() {
        bisiIsAMember();
        listener.onSubscribe(subscribe("phone", "sub-1", "/topic/spaces/" + room + "/events", bisi));

        listener.onSpaceEvent(new SpaceEvent(SpaceEvent.Type.MEMBER_LEFT, room, Instant.now(),
            new ParticipantResponse(bisiId, "Bisi", Participant.Role.MEMBER, true, Instant.now())));

        verify(connections).close("phone", ConnectionRegistry.NO_LONGER_A_MEMBER);
    }

    @Test
    void aRemovalWithoutParticipantDataClosesNothing() {
        listener.onSpaceEvent(new SpaceEvent(SpaceEvent.Type.MEMBER_REMOVED, room, Instant.now(), null));
        verify(connections, never()).close(any(), any());
    }

    @Test
    void goingAwayWithoutHavingBeenOnlineAnnouncesNothing() {
        listener.onUnsubscribe(new SessionUnsubscribeEvent(this, frame(StompCommand.UNSUBSCRIBE, "conn-1", "sub-1", null), auth(bisi)));
        listener.onDisconnect(new SessionDisconnectEvent(this, frame(StompCommand.DISCONNECT, "conn-1", null, null),
            "conn-1", CloseStatus.NORMAL, auth(bisi)));

        verifyNoInteractions(events);
    }

    @Test
    void otherRoomEventsCloseNothing() {
        listener.onSpaceEvent(new SpaceEvent(SpaceEvent.Type.ROLE_CHANGED, room, Instant.now(),
            new ParticipantResponse(bisiId, "Bisi", Participant.Role.ADMIN, false, Instant.now())));
        verify(connections, never()).close(any(), any());
    }

    // ---- helpers -------------------------------------------------------------

    private SessionSubscribeEvent subscribe(String connection, String subscription, String destination, AuthPrincipal who) {
        return new SessionSubscribeEvent(this, frame(StompCommand.SUBSCRIBE, connection, subscription, destination), auth(who));
    }

    private static Message<byte[]> frame(StompCommand command, String connection, String subscription, String destination) {
        StompHeaderAccessor headers = StompHeaderAccessor.create(command);
        headers.setSessionId(connection);
        if (subscription != null) {
            headers.setSubscriptionId(subscription);
        }
        if (destination != null) {
            headers.setDestination(destination);
        }
        return MessageBuilder.createMessage(new byte[0], headers.getMessageHeaders());
    }

    private static Principal auth(AuthPrincipal who) {
        if (who == null) {
            return null;
        }
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "HS256").subject(who.participantId().toString()).build();
        return new PrincipalAuthentication(who, jwt);
    }
}
