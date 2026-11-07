package com.mywatchmate.watch.dto;

import com.mywatchmate.connect.ChatService;
import com.mywatchmate.watch.Participant;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

@Schema(description = "Someone currently in the room")
public record ParticipantResponse(
    @Schema(description = "Participant id (use it to change role or remove)") UUID id,
    @Schema(example = "Bisi") String displayName,
    @Schema(example = "MEMBER") Participant.Role role,
    @Schema(description = "Joined without an account") boolean guest,
    Instant joinedAt,
    @Schema(description = "A host muted them in the chat, and it hasn't run out") boolean chatMuted,
    @Schema(description = "When the mute runs out; null when not muted, or muted until a host unmutes them",
        nullable = true) Instant chatMutedUntil
) {
    /** As of now: whether a timed mute has run out depends on the time. */
    public static ParticipantResponse from(Participant p, Instant now) {
        boolean muted = p.isChatMuted(now);
        return new ParticipantResponse(p.getId(), p.displayName(), p.getRole(), p.isGuest(), p.getJoinedAt(), muted,
            muted ? ChatService.shownUntil(p.getChatMutedUntil()) : null);
    }

    /** For events published as something happens (the wall clock is "now"). */
    public static ParticipantResponse from(Participant p) {
        return from(p, Instant.now());
    }
}
