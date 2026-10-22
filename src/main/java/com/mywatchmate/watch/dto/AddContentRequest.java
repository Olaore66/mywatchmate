package com.mywatchmate.watch.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AddContentRequest(
    @Schema(description = "YouTube, Vimeo, or a direct .mp4/.webm/.m3u8 link",
        example = "https://youtu.be/dQw4w9WgXcQ") @NotBlank @Size(max = 2048) String url,
    @Schema(description = "Start it for everyone now (default true); false just adds it to the room's list", nullable = true)
    Boolean playNow
) {
}
