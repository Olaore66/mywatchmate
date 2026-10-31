package com.mywatchmate.connect.call.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

@Schema(description = "Connect with the LiveKit SDK: room.connect(url, token). Ask again for a new token if it expires before connecting.")
public record CallJoinResponse(
    @Schema(description = "LiveKit server", example = "wss://mywatchmate-staging-abc123.livekit.cloud") String url,
    @Schema(description = "Join token for this person and this room only") String token,
    @Schema(description = "LiveKit room name (one per space)", example = "space-1b2c3d4e-5f6a-4b7c-8d9e-0f1a2b3c4d5e")
    String roomName,
    @Schema(description = "Your identity in the call: your participant id in the space") String identity,
    @Schema(description = "Connect before this") Instant expiresAt
) {
}
