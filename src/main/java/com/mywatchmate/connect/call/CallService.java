package com.mywatchmate.connect.call;

import com.mywatchmate.auth.AuthPrincipal;
import com.mywatchmate.common.ApiException;
import com.mywatchmate.connect.call.dto.CallJoinResponse;
import com.mywatchmate.connect.call.dto.CallRoster;
import com.mywatchmate.watch.Participant;
import com.mywatchmate.watch.SpaceAccess;
import com.mywatchmate.watch.SpaceAccess.Membership;
import com.mywatchmate.watch.SpaceEvent;
import com.mywatchmate.watch.SpaceEvents;
import com.mywatchmate.watch.dto.ParticipantResponse;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.json.JsonParseException;
import org.springframework.boot.json.JsonParserFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Video calls in a room, through LiveKit. Our server never carries audio or video: it checks the person is
 * in the room and hands their app a short-lived join token; the app connects to LiveKit directly. LiveKit's
 * webhooks tell us who joined or left, and the room hears CALL_UPDATED. Each space has one call room.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CallService {

    static final String ROOM_PREFIX = "space-";
    /** LiveKit webhook events that change who's in the call or what they're sending. */
    static final Set<String> ROSTER_EVENTS = Set.of("participant_joined", "participant_left", "track_published",
        "track_unpublished", "room_finished");

    private final LiveKitProperties props;
    private final LiveKitTokens tokens;
    private final LiveKitRoomClient livekit;
    private final SpaceAccess access;
    private final SpaceEvents events;
    private final Clock clock;

    /** Anyone in the room (guests too). */
    @Transactional(readOnly = true)
    public CallJoinResponse join(AuthPrincipal principal, UUID spaceId) {
        requireConfigured();
        Membership membership = access.require(principal, spaceId).requireNotArchived();
        Participant me = membership.participant();
        Instant now = clock.instant();
        String room = roomName(spaceId);
        String metadata = "{\"guest\":" + me.isGuest() + ",\"role\":\"" + membership.level().name() + "\"}";
        String token = tokens.joinToken(room, me.getId().toString(), me.displayName(), metadata, now);
        return new CallJoinResponse(props.url(), token, room, me.getId().toString(), now.plus(props.tokenTtl()));
    }

    @Transactional(readOnly = true)
    public CallRoster roster(AuthPrincipal principal, UUID spaceId) {
        requireConfigured();
        access.require(principal, spaceId);
        return CallRoster.of(livekit.listParticipants(roomName(spaceId)));
    }

    /** From LiveKit. Anything not signed with our secret is refused. */
    public void onWebhook(String authorization, String body) {
        requireConfigured();
        if (!tokens.isGenuineWebhook(authorization, body, clock.instant())) {
            throw ApiException.unauthorized("INVALID_WEBHOOK_SIGNATURE", "This webhook isn't signed by our LiveKit project.");
        }
        Map<String, Object> event;
        try {
            event = JsonParserFactory.getJsonParser().parseMap(body);
        } catch (JsonParseException e) {
            throw ApiException.badRequest("MALFORMED_REQUEST", "The webhook body isn't JSON.");
        }
        if (!ROSTER_EVENTS.contains(String.valueOf(event.get("event")))) {
            return;
        }
        Object roomName = event.get("room") instanceof Map<?, ?> room ? room.get("name") : null;
        spaceOf(roomName == null ? null : roomName.toString()).ifPresent(this::announce);
    }

    /** Someone removed from (or leaving) the room is taken out of its call too. */
    @TransactionalEventListener(fallbackExecution = true)
    public void onSpaceEvent(SpaceEvent event) {
        if (!props.configured()
            || (event.type() != SpaceEvent.Type.MEMBER_REMOVED && event.type() != SpaceEvent.Type.MEMBER_LEFT)
            || !(event.data() instanceof ParticipantResponse gone)) {
            return;
        }
        try {
            livekit.removeParticipant(roomName(event.spaceId()), gone.id().toString());
        } catch (RuntimeException e) {
            log.warn("Couldn't remove {} from the call in space {}: {}", gone.id(), event.spaceId(), e.getMessage());
        }
    }

    public static String roomName(UUID spaceId) {
        return ROOM_PREFIX + spaceId;
    }

    static Optional<UUID> spaceOf(String roomName) {
        if (roomName == null || !roomName.startsWith(ROOM_PREFIX)) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(roomName.substring(ROOM_PREFIX.length())));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** Tells the room who's in the call now. LiveKit is asked, so it's always right after a restart too. */
    private void announce(UUID spaceId) {
        try {
            events.publish(spaceId, SpaceEvent.Type.CALL_UPDATED, CallRoster.of(livekit.listParticipants(roomName(spaceId))));
        } catch (RuntimeException e) {
            log.warn("Couldn't refresh the call in space {}: {}", spaceId, e.getMessage());
        }
    }

    private void requireConfigured() {
        if (!props.configured()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "CALLS_NOT_CONFIGURED", "Video calls aren't set up yet.");
        }
    }
}
