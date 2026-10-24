package com.mywatchmate.live.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

@Schema(description = "A button press that applies to everyone in the room")
public record PlaybackCommand(
    @Schema(example = "SEEK") @NotNull Action action,
    @Schema(description = "SEEK only: where to jump to, in milliseconds from the start", example = "754000", nullable = true)
    @PositiveOrZero Long positionMs
) {
    public enum Action { PLAY, PAUSE, SEEK }
}
