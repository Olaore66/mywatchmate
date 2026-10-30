package com.mywatchmate.invite.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

public record EmailInviteRequest(
    @Schema(description = "Up to 20 addresses", example = "[\"friend@example.com\", \"member@example.com\"]")
    @NotEmpty @Size(max = 20) List<@NotNull @Size(max = 254) String> emails,
    @Schema(description = "Optional note shown in the email (plain text; links are removed)",
        example = "Join us for Sunday service!", nullable = true)
    @Size(max = 500) String customMessage
) {
}
