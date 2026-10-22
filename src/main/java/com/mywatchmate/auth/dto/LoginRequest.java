package com.mywatchmate.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

public record LoginRequest(
    @Schema(example = "ada@example.com") @NotBlank String email,
    @Schema(example = "correct-horse-battery") @NotBlank String password
) {
}
