package com.mywatchmate.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

public record GuestRefreshRequest(
    @Schema(description = "The deviceToken returned by /auth/guest", example = "Zt8…opaque…1aQ")
    @NotBlank String deviceToken
) {
}
