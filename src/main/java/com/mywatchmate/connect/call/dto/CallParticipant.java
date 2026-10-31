package com.mywatchmate.connect.call.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

@Schema(description = "Someone in the room's call")
public record CallParticipant(
    @Schema(description = "Their participant id in the space") UUID participantId,
    @Schema(example = "Bisi") String displayName,
    @Schema(nullable = true) Instant joinedAt,
    @Schema(description = "Camera on (published and not muted)") boolean camera,
    @Schema(description = "Microphone on (published and not muted)") boolean microphone,
    boolean screenSharing
) {
}
