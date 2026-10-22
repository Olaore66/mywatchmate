package com.mywatchmate.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

@Schema(description = "Tokens for a guest in one space")
public record GuestTokenResponse(
    @Schema(description = "Send as 'Authorization: Bearer <accessToken>'", example = "eyJhbGciOiJIUzI1NiJ9…")
    String accessToken,
    @Schema(example = "Bearer") String tokenType,
    @Schema(description = "Access token lifetime in seconds", example = "900") long expiresIn,
    @Schema(description = "Long-lived; keep it on the device and use it at /auth/guest/refresh. "
        + "Only returned when joining, null on refresh.", example = "Zt8…opaque…1aQ", nullable = true)
    String deviceToken,
    @Schema(example = "7a9b0c1d-2e3f-4a5b-8c6d-7e8f9a0b1c2d") UUID participantId,
    @Schema(example = "1b2c3d4e-5f6a-4b7c-8d9e-0f1a2b3c4d5e") UUID spaceId,
    @Schema(example = "Bisi") String displayName
) {
}
