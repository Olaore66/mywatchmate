package com.mywatchmate.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

public record RefreshRequest(
    @Schema(description = "The refreshToken from the last login or refresh", example = "q3X9…opaque…7Zk")
    @NotBlank String refreshToken
) {
}
