package com.mywatchmate.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ResetPasswordRequest(
    @Schema(description = "The `token` from the emailed link") @NotBlank @Size(max = 200) String token,
    @Schema(description = "8 to 72 characters", example = "new-battery-staple") @NotBlank @Size(min = 8, max = 72) String newPassword
) {
}
