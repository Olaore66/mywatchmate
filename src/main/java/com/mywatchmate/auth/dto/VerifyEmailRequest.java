package com.mywatchmate.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record VerifyEmailRequest(
    @Schema(description = "The `token` from the emailed link") @NotBlank @Size(max = 200) String token
) {
}
