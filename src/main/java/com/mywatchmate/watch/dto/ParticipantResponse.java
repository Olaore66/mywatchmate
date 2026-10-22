package com.mywatchmate.watch.dto;

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
    Instant joinedAt
) {
    public static ParticipantResponse from(Participant p) {
        return new ParticipantResponse(p.getId(), p.displayName(), p.getRole(), p.isGuest(), p.getJoinedAt());
    }
}
