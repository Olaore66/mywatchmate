package com.mywatchmate.sessions.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import java.util.UUID;

@Schema(description = "Optional details for starting a session right now")
public record WatchNowRequest(
    @Schema(example = "Quick catch-up", nullable = true) @Size(max = 150) String title,
    @Schema(description = "A video in this room; omit to use whatever is playing", nullable = true) UUID contentId
) {
}
