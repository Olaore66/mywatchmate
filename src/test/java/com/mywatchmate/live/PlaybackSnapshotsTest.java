package com.mywatchmate.live;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.mywatchmate.auth.AuthPrincipal;
import com.mywatchmate.auth.PrincipalAuthentication;
import com.mywatchmate.common.ApiException;
import com.mywatchmate.live.dto.PlaybackResponse;
import java.security.Principal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.converter.JacksonJsonMessageConverter;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.socket.messaging.SessionSubscribeEvent;

@ExtendWith(MockitoExtension.class)
class PlaybackSnapshotsTest {

    @Mock PlaybackService playback;
    @Mock MessageChannel clients;

    private final UUID room = UUID.randomUUID();
    private final AuthPrincipal bisi = AuthPrincipal.guest(UUID.randomUUID(), room);
    private PlaybackSnapshots snapshots;

    @BeforeEach
    void setUp() {
        snapshots = new PlaybackSnapshots(playback, clients, new JacksonJsonMessageConverter(),
            Clock.fixed(Instant.parse("2026-11-02T00:00:00Z"), ZoneOffset.UTC));
    }

    private static PlaybackResponse state(UUID contentId) {
        return new PlaybackResponse(contentId, PlaybackState.Status.PLAYING, 5000, 1000, null, false, 7, null);
    }

    private SessionSubscribeEvent subscribe(String destination, Principal who) {
        StompHeaderAccessor headers = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        headers.setSessionId("conn-1");
        headers.setSubscriptionId("sub-1");
        headers.setDestination(destination);
        headers.setUser(who);
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], headers.getMessageHeaders());
        return new SessionSubscribeEvent(this, message, who);
    }

    private Principal signedIn() {
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").subject("s").build();
        return new PrincipalAuthentication(bisi, jwt);
    }

    private String roomTopic() {
        return "/topic/spaces/" + room + "/events";
    }

    @Test
    void theSubscriberAloneGetsTheCurrentState() {
        when(playback.get(bisi, room)).thenReturn(state(UUID.randomUUID()));
        when(clients.send(any(Message.class))).thenReturn(true);

        snapshots.onSubscribe(subscribe(roomTopic(), signedIn()));

        ArgumentCaptor<Message<?>> sent = ArgumentCaptor.captor();
        verify(clients).send(sent.capture());
        SimpMessageHeaderAccessor headers = SimpMessageHeaderAccessor.wrap(sent.getValue());
        assertThat(headers.getSessionId()).isEqualTo("conn-1");
        assertThat(headers.getSubscriptionId()).isEqualTo("sub-1");
        assertThat(headers.getDestination()).isEqualTo(roomTopic());
        assertThat(new String((byte[]) sent.getValue().getPayload()))
            .contains("\"type\":\"PLAYBACK_CHANGED\"", "\"version\":7", "\"positionMs\":5000");
    }

    @Test
    void aRoomWithNothingPlayingSendsNothing() {
        when(playback.get(bisi, room)).thenReturn(state(null));

        snapshots.onSubscribe(subscribe(roomTopic(), signedIn()));

        verify(clients, never()).send(any(Message.class));
    }

    @Test
    void someoneRemovedJustGetsNothing() {
        when(playback.get(bisi, room)).thenThrow(ApiException.notFound("SPACE_NOT_FOUND", "gone"));

        snapshots.onSubscribe(subscribe(roomTopic(), signedIn()));

        verifyNoInteractions(clients);
    }

    @Test
    void otherDestinationsAndStrangersAreLeftAlone() {
        snapshots.onSubscribe(subscribe("/user/queue/errors", signedIn()));
        snapshots.onSubscribe(subscribe("/topic/spaces/" + room + "/other", signedIn()));
        snapshots.onSubscribe(subscribe(roomTopic(), () -> "not-ours"));

        verifyNoInteractions(playback, clients);
    }
}
