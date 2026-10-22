package com.mywatchmate.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record GuestJoinRequest(
    @Schema(description = "The token from the space's invite link", example = "k7Pq2rXv")
    @NotBlank @Size(max = 64) String inviteToken,
    @Schema(description = "Name shown to others in the room", example = "Bisi")
    @NotBlank @Size(max = 100) String displayName
) {
}
