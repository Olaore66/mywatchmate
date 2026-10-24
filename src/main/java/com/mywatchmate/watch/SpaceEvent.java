package com.mywatchmate.watch;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

/**
 * Pushed to {@code /topic/spaces/{spaceId}/events} so every member's screen updates at once.
 * See docs/realtime-events.md for each type's {@code data}.
 */
@Schema(description = "Real-time room event (WebSocket)")
public record SpaceEvent(Type type, UUID spaceId, Instant at, Object data) {

    public enum Type {
        MEMBER_JOINED, MEMBER_LEFT, MEMBER_REMOVED, ROLE_CHANGED, OWNERSHIP_TRANSFERRED,
        SPACE_UPDATED, SPACE_ARCHIVED, SPACE_RESTORED,
        CONTENT_ADDED, CONTENT_REMOVED, NOW_PLAYING_CHANGED,
        SCHEDULE_UPDATED, SESSION_SCHEDULED, SESSION_STARTED, SESSION_ENDED, SESSION_CANCELLED,
        PLAYBACK_CHANGED, PRESENCE_CHANGED,
        ATTENDANCE_FINALISED, NOTE_ADDED, NOTE_UPDATED, NOTE_DELETED
    }
}
