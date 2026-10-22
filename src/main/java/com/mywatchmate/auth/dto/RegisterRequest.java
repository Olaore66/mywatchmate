package com.mywatchmate.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
    @Schema(example = "ada@example.com") @NotBlank @Email @Size(max = 320) String email,
    @Schema(description = "8 to 72 characters", example = "correct-horse-battery") @NotBlank @Size(min = 8, max = 72) String password,
    @Schema(example = "Ada Okafor") @NotBlank @Size(max = 100) String displayName,
    @Schema(description = "IANA timezone; defaults to UTC", example = "Europe/London", nullable = true) @Size(max = 64) String timezone
) {
}
