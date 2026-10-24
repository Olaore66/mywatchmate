package com.mywatchmate.watch.dto;

import com.mywatchmate.live.dto.PlaybackResponse;
import com.mywatchmate.watch.Space;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

@Schema(description = "A room as seen by the caller")
public record SpaceResponse(
    @Schema(example = "1b2c3d4e-5f6a-4b7c-8d9e-0f1a2b3c4d5e") UUID id,
    @Schema(example = "6am Prayer Room") String title,
    @Schema(nullable = true) String description,
    Space.AudienceType audienceType,
    boolean allowGuests,
    boolean membersCanAddContent,
    boolean membersCanControlPlayback,
    @Schema(description = "The room owner (user id)") PersonRef owner,
    @Schema(description = "The caller's role here", allowableValues = {"OWNER", "ADMIN", "MEMBER", "GUEST"}) String myRole,
    @Schema(description = "The caller's participant id in this room") UUID myParticipantId,
    @Schema(description = "Whether the caller may add videos / change what's playing") boolean canAddContent,
    @Schema(description = "Whether the caller may play, pause and seek for everyone") boolean canControlPlayback,
    @Schema(example = "4") long memberCount,
    @Schema(example = "50") int maxMembers,
    @Schema(description = "What everyone is watching; start this player on load", nullable = true) ContentResponse nowPlaying,
    @Schema(description = "Where that video is right now; line the player up with it") PlaybackResponse playback,
    @Schema(description = "People with the room open right now", example = "3") int onlineCount,
    @Schema(description = "The caller's streak in days; null for guests", example = "5", nullable = true) Integer myStreak,
    @Schema(description = "Chat messages from others since the caller last read the chat", example = "3") long unreadCount,
    @Schema(description = "When the caller's chat mute ends; null if not muted or muted until a host unmutes them",
        nullable = true) Instant chatMutedUntil,
    @Schema(description = "The caller can't send chat messages right now") boolean chatMuted,
    @Schema(description = "Set when archived (owners only can still see it)", nullable = true) Instant archivedAt,
    Instant createdAt
) {
}
