package com.mywatchmate.watch.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LinkPreviewRequest(
    @Schema(example = "https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=42s") @NotBlank @Size(max = 2048) String url
) {
}
