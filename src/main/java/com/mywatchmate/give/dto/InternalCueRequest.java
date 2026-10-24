package com.mywatchmate.give.dto;

import com.mywatchmate.give.CueDetection;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.UUID;

@Schema(description = "A payment cue the AI worker found in a live session's video")
public record InternalCueRequest(
    @NotNull UUID sessionId,
    @Schema(description = "What the QR code decoded to") @NotBlank @Size(max = 4096) String rawValue,
    @Schema(description = "Default QR", nullable = true) CueDetection.CueType cueType,
    @Schema(example = "0.97", nullable = true) @DecimalMin("0") @DecimalMax("1") BigDecimal confidence,
    @Schema(description = "Where in the video", nullable = true) @PositiveOrZero Integer positionSeconds
) {
}
