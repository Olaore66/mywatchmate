package com.mywatchmate.live.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

@Schema(description = "Someone with the room open right now")
public record OnlineViewer(
    @Schema(description = "Their participant id in this room") UUID participantId,
    @Schema(example = "Bisi") String displayName,
    @Schema(description = "Joined without an account") boolean guest
) {
}
