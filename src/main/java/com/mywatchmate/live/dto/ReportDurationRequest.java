package com.mywatchmate.live.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

@Schema(description = "How long the video the player has loaded is")
public record ReportDurationRequest(
    @Schema(description = "The video this is about (`contentId` in the playback state)") @NotNull UUID contentId,
    @Schema(description = "Its length in milliseconds", example = "212000") @NotNull @Min(1000) @Max(86_400_000) Long durationMs
) {
}
