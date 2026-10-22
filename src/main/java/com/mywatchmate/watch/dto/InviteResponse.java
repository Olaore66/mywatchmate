package com.mywatchmate.watch.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record InviteResponse(
    @Schema(example = "k7Pq2rXvB9mT4wZa") String inviteToken,
    @Schema(description = "Share this", example = "http://localhost:3000/join/k7Pq2rXvB9mT4wZa") String inviteUrl
) {
}
