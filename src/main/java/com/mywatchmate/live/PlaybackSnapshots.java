package com.mywatchmate.live;

import com.mywatchmate.auth.PrincipalAuthentication;
import com.mywatchmate.common.ApiException;
import com.mywatchmate.live.dto.PlaybackResponse;
import com.mywatchmate.watch.SpaceEvent;
import com.mywatchmate.watch.SpaceEvents;
import java.time.Clock;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.converter.MessageConverter;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionSubscribeEvent;

/**
 * Snapshot, then changes: whoever subscribes to a room gets the current playback state straight away, as a
 * PLAYBACK_CHANGED sent to that one subscription. Events aren't kept, so someone whose connection dropped would
 * otherwise miss every press made while they were away and carry on from an old state, with nothing to tell them.
 * The version keeps it safe: a client that already has this state, or a newer one, ignores it.
 */
@Slf4j
@Component
public class PlaybackSnapshots {

    private final PlaybackService playback;
    private final SimpMessagingTemplate toOneClient;
    private final Clock clock;

    public PlaybackSnapshots(PlaybackService playback, @Qualifier("clientOutboundChannel") MessageChannel clients,
                             @Qualifier("brokerMessageConverter") MessageConverter converter, Clock clock) {
        this.playback = playback;
        this.toOneClient = new SimpMessagingTemplate(clients);
        this.toOneClient.setMessageConverter(converter);
        this.clock = clock;
    }

    @EventListener
    public void onSubscribe(SessionSubscribeEvent event) {
        SimpMessageHeaderAccessor headers = SimpMessageHeaderAccessor.wrap(event.getMessage());
        String destination = headers.getDestination();
        if (!(event.getUser() instanceof PrincipalAuthentication auth) || headers.getSubscriptionId() == null) {
            return;
        }
        SpaceEvents.roomOf(destination)
            .filter(spaceId -> SpaceEvents.topic(spaceId).equals(destination))
            .ifPresent(spaceId -> send(auth, spaceId, destination, headers));
    }

    private void send(PrincipalAuthentication auth, UUID spaceId, String destination, SimpMessageHeaderAccessor subscriber) {
        try {
            PlaybackResponse state = playback.get(auth.getPrincipal(), spaceId);
            if (state.contentId() == null) {
                return;
            }
            SimpMessageHeaderAccessor to = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
            to.setSessionId(subscriber.getSessionId());
            to.setSubscriptionId(subscriber.getSubscriptionId());
            to.setLeaveMutable(true);
            toOneClient.convertAndSend(destination,
                new SpaceEvent(SpaceEvent.Type.PLAYBACK_CHANGED, spaceId, clock.instant(), state), to.getMessageHeaders());
        } catch (ApiException e) {
            // Removed from the room between subscribing and getting here: nothing to send them
            log.debug("No playback snapshot for {}: {}", spaceId, e.getCode());
        }
    }
}
