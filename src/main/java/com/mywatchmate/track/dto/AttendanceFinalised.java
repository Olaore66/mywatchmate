package com.mywatchmate.track.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

@Schema(description = "Sent as ATTENDANCE_FINALISED when a session ends: \"8 of 10 attended\"")
public record AttendanceFinalised(
    UUID sessionId,
    @Schema(example = "8") int attendedCount,
    @Schema(description = "Members in the room when it started, plus anyone who joined later and counted", example = "10")
    int expectedCount
) {
}
