package com.mywatchmate.give.dto;

import com.mywatchmate.give.CueDetection;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

@Schema(description = "What happened to a cue: SHOWN to the room, or REJECTED / BLOCKED with a reason")
public record CueOutcome(
    @Schema(description = "Null when nothing was recorded (e.g. already showing)", nullable = true) UUID detectionId,
    CueDetection.Status status,
    @Schema(example = "NOT_PAYAZA", nullable = true) String reason
) {
}
