package com.mywatchmate.auth.dto;

import com.mywatchmate.identity.dto.MeResponse;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Tokens for a registered user")
public record TokenResponse(
    @Schema(description = "Send as 'Authorization: Bearer <accessToken>'", example = "eyJhbGciOiJIUzI1NiJ9…")
    String accessToken,
    @Schema(example = "Bearer") String tokenType,
    @Schema(description = "Access token lifetime in seconds", example = "900") long expiresIn,
    @Schema(description = "Single-use; exchange at /auth/refresh for a new pair. Store securely.", example = "q3X9…opaque…7Zk")
    String refreshToken,
    MeResponse user
) {
    public static TokenResponse of(String accessToken, long expiresIn, String refreshToken, MeResponse user) {
        return new TokenResponse(accessToken, "Bearer", expiresIn, refreshToken, user);
    }
}
