package com.mywatchmate.give.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record OpenGiftRequest(
    @Schema(description = "Hide my name from the room and the hosts' summary. Default false.", nullable = true)
    Boolean anonymous
) {
}
