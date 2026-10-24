package com.mywatchmate.give.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record BlockLinkRequest(
    @Schema(description = "The payment link to block in this room") @NotBlank @Size(max = 2048) String url,
    @Schema(example = "Not our church's account", nullable = true) @Size(max = 300) String reason
) {
}
