package com.mywatchmate.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "The ID token Google's sign-in button gave the app")
public record GoogleSignInRequest(
    @Schema(description = "`credential` from Google Identity Services' callback (a JWT)") @NotBlank @Size(max = 8192)
    String idToken,
    @Schema(description = "IANA timezone for a new account (ignored for existing ones); default UTC",
        example = "Europe/London", nullable = true) @Size(max = 64) String timezone
) {
}
