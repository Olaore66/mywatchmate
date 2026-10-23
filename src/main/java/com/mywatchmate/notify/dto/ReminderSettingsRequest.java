package com.mywatchmate.notify.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record ReminderSettingsRequest(
    @Schema(description = "Remind me before sessions in this room", example = "true") @NotNull Boolean enabled,
    @Schema(description = "How long before; default 10", example = "15", nullable = true) @Min(0) @Max(1440) Integer minutesBefore
) {
}
