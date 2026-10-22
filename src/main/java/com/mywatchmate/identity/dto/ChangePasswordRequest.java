package com.mywatchmate.identity.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ChangePasswordRequest(
    @Schema(example = "old-correct-horse") @NotBlank String currentPassword,
    @Schema(description = "8 to 72 characters", example = "new-battery-staple") @NotBlank @Size(min = 8, max = 72) String newPassword
) {
}
