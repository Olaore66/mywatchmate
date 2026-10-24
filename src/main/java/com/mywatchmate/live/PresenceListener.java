package com.mywatchmate.live;

import com.mywatchmate.auth.PrincipalAuthentication;
import com.mywatchmate.live.dto.OnlineViewer;
import com.mywatchmate.live.dto.PresenceResponse;
import com.mywatchmate.watch.SpaceAccess;
import com.mywatchmate.watch.SpaceEvent;
import com.mywatchmate.watch.SpaceEvents;
import com.mywatchmate.watch.dto.ParticipantResponse;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;
import org.springframework.web.socket.messaging.SessionSubscribeEvent;
import org.springframework.web.socket.messaging.SessionUnsubscribeEvent;

/**
 * Feeds {@link PresenceTracker} from WebSocket activity and tells the room (PRESENCE_CHANGED) when
 * someone comes online or goes offline. Subscriptions only get here once the auth check allowed them.
 * Also closes the open connections of people removed from (or leaving) a room, so they stop hearing it.
 */
@Component
@RequiredArgsConstructor
public class PresenceListener {

    private final PresenceTracker presence;
    private final SpaceAccess access;
    private final SpaceEvents events;
    private final ConnectionRegistry connections;

    @EventListener
    public void onSubscribe(SessionSubscribeEvent event) {
        SimpMessageHeaderAccessor headers = SimpMessageHeaderAccessor.wrap(event.getMessage());
        if (!(event.getUser() instanceof PrincipalAuthentication auth)) {
            return;
        }
        SpaceEvents.roomOf(headers.getDestination()).ifPresent(spaceId ->
            access.activeParticipant(auth.getPrincipal(), spaceId).ifPresent(member -> {
                if (presence.subscribed(headers.getSessionId(), headers.getSubscriptionId(), spaceId, viewer(member))) {
                    announce(spaceId);
                }
            }));
    }

    @EventListener
    public void onUnsubscribe(SessionUnsubscribeEvent event) {
        SimpMessageHeaderAccessor headers = SimpMessageHeaderAccessor.wrap(event.getMessage());
        UUID spaceId = presence.unsubscribed(headers.getSessionId(), headers.getSubscriptionId());
        if (spaceId != null) {
            announce(spaceId);
        }
    }

    /** Also covers connections that just dropped (phone went to sleep, network lost), once heartbeats time out. */
    @EventListener
    public void onDisconnect(SessionDisconnectEvent event) {
        presence.disconnected(event.getSessionId()).forEach(this::announce);
    }

    /** After the change is saved: the removed person's open connections on that room are closed. */
    @TransactionalEventListener(fallbackExecution = true)
    public void onSpaceEvent(SpaceEvent event) {
        if ((event.type() == SpaceEvent.Type.MEMBER_REMOVED || event.type() == SpaceEvent.Type.MEMBER_LEFT)
            && event.data() instanceof ParticipantResponse gone) {
            presence.connectionsOf(event.spaceId(), gone.id())
                .forEach(id -> connections.close(id, ConnectionRegistry.NO_LONGER_A_MEMBER));
        }
    }

    private void announce(UUID spaceId) {
        events.publish(spaceId, SpaceEvent.Type.PRESENCE_CHANGED, PresenceResponse.of(presence.online(spaceId)));
    }

    private static OnlineViewer viewer(ParticipantResponse member) {
        return new OnlineViewer(member.id(), member.displayName(), member.guest());
    }
}
