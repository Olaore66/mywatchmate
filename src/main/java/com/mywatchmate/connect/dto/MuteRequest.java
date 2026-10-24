package com.mywatchmate.connect.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Positive;

public record MuteRequest(
    @Schema(description = "How long, up to 30 days; leave out to mute until unmuted", example = "60", nullable = true)
    @Positive @Max(43_200) Integer minutes
) {
}
