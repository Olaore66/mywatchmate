package com.mywatchmate.connect.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ChatMessageRequest(
    @Schema(example = "Amen 🙏") @NotBlank @Size(max = 2000) String body
) {
}
