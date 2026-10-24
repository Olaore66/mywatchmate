package com.mywatchmate.connect.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

/** Sent as TYPING (emoji null) or REACTION. Never stored. */
@Schema(description = "Someone typing, or a floating reaction")
public record ChatSignal(
    UUID participantId,
    @Schema(example = "Bisi") String displayName,
    @Schema(description = "REACTION only", example = "🙏", nullable = true) String emoji
) {
}
