package com.mywatchmate.give.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

@Schema(description = "Lets the AI cue worker join a room's call hidden and view-only, to read a shared screen")
public record WatchTokenResponse(
    @Schema(example = "wss://mywatchmate.livekit.cloud") String url,
    String token,
    @Schema(example = "space-1b2c3d4e-…") String roomName,
    @Schema(example = "cue-worker") String identity,
    @Schema(description = "Connect before this; the connection itself can last as long as needed") Instant expiresAt
) {
}
