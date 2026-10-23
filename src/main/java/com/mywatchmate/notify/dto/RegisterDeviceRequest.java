package com.mywatchmate.notify.dto;

import com.mywatchmate.identity.DeviceToken;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record RegisterDeviceRequest(
    @Schema(example = "ANDROID") @NotNull DeviceToken.Platform platform,
    @Schema(description = "Push token from Firebase (Android/web) or APNs (iOS)", example = "fcm-token-abc123")
    @NotBlank @Size(max = 512) String token
) {
}
