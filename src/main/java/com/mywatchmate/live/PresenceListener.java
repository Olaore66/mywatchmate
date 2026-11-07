package com.mywatchmate.live;

import com.mywatchmate.auth.PrincipalAuthentication;
import com.mywatchmate.cluster.ClusterBus;
import com.mywatchmate.live.dto.OnlineViewer;
import com.mywatchmate.live.dto.PresenceResponse;
import com.mywatchmate.watch.SpaceAccess;
import com.mywatchmate.watch.SpaceEvent;
import com.mywatchmate.watch.SpaceEvents;
import com.mywatchmate.watch.dto.ParticipantResponse;
import java.util.UUID;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;
import org.springframework.web.socket.messaging.SessionSubscribeEvent;
import org.springframework.web.socket.messaging.SessionUnsubscribeEvent;

/**
 * Feeds {@link PresenceTracker} from WebSocket activity and tells the room (PRESENCE_CHANGED) when
 * someone comes online or goes offline, across all copies of the app ({@link RoomPresence}). Subscriptions
 * only get here once the auth check allowed them. Also closes the open connections of people removed from
 * (or leaving) a room, on whichever copy they're connected to, so they stop hearing it.
 */
@Component
public class PresenceListener {

    static final String KICK_CHANNEL = "presence-kick";

    /** "Close this person's connections to this room", to every copy. */
    public record Kick(UUID spaceId, UUID participantId) {
    }

    private final PresenceTracker presence;
    private final RoomPresence rooms;
    private final SpaceAccess access;
    private final SpaceEvents events;
    private final ConnectionRegistry connections;
    private final ClusterBus bus;

    public PresenceListener(PresenceTracker presence, RoomPresence rooms, SpaceAccess access, SpaceEvents events,
                            ConnectionRegistry connections, ClusterBus bus) {
        this.presence = presence;
        this.rooms = rooms;
        this.access = access;
        this.events = events;
        this.connections = connections;
        this.bus = bus;
        bus.subscribe(KICK_CHANNEL, Kick.class, this::closeHere);
    }

    @EventListener
    public void onSubscribe(SessionSubscribeEvent event) {
        SimpMessageHeaderAccessor headers = SimpMessageHeaderAccessor.wrap(event.getMessage());
        if (!(event.getUser() instanceof PrincipalAuthentication auth)) {
            return;
        }
        SpaceEvents.roomOf(headers.getDestination()).ifPresent(spaceId ->
            access.activeParticipant(auth.getPrincipal(), spaceId).ifPresent(member -> {
                if (presence.subscribed(headers.getSessionId(), headers.getSubscriptionId(), spaceId, viewer(member))) {
                    changed(spaceId);
                }
            }));
    }

    @EventListener
    public void onUnsubscribe(SessionUnsubscribeEvent event) {
        SimpMessageHeaderAccessor headers = SimpMessageHeaderAccessor.wrap(event.getMessage());
        UUID spaceId = presence.unsubscribed(headers.getSessionId(), headers.getSubscriptionId());
        if (spaceId != null) {
            changed(spaceId);
        }
    }

    /** Also covers connections that just dropped (phone went to sleep, network lost), once heartbeats time out. */
    @EventListener
    public void onDisconnect(SessionDisconnectEvent event) {
        presence.disconnected(event.getSessionId()).forEach(this::changed);
    }

    /** After the change is saved: the removed person's open connections on that room are closed, on every copy. */
    @TransactionalEventListener(fallbackExecution = true)
    public void onSpaceEvent(SpaceEvent event) {
        if ((event.type() == SpaceEvent.Type.MEMBER_REMOVED || event.type() == SpaceEvent.Type.MEMBER_LEFT)
            && event.data() instanceof ParticipantResponse gone) {
            bus.publish(KICK_CHANNEL, new Kick(event.spaceId(), gone.id()));
        }
    }

    void closeHere(Kick kick) {
        presence.connectionsOf(kick.spaceId(), kick.participantId())
            .forEach(id -> connections.close(id, ConnectionRegistry.NO_LONGER_A_MEMBER));
    }

    /** Someone came or went through this copy: tell the room if its overall list changed. */
    private void changed(UUID spaceId) {
        if (rooms.localChanged(spaceId)) {
            events.publish(spaceId, SpaceEvent.Type.PRESENCE_CHANGED, PresenceResponse.of(rooms.online(spaceId)));
        }
    }

    private static OnlineViewer viewer(ParticipantResponse member) {
        return new OnlineViewer(member.id(), member.displayName(), member.guest());
    }
}
