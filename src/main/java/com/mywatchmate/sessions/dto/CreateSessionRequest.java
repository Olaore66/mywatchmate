package com.mywatchmate.sessions.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;

@Schema(description = "A one-off session at a set time")
public record CreateSessionRequest(
    @Schema(description = "Start time, ISO-8601 with offset or Z", example = "2026-11-08T17:00:00Z") @NotNull @Future Instant scheduledAt,
    @Schema(example = "Sunday film night", nullable = true) @Size(max = 150) String title,
    @Schema(description = "A video in this room; omit to use whatever is playing then", nullable = true) UUID contentId
) {
}
