package com.mywatchmate.watch.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record NowPlayingRequest(
    @Schema(description = "A video already in this room") @NotNull UUID contentId
) {
}
