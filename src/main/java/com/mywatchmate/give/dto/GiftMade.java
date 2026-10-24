package com.mywatchmate.give.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** Sent as GIFT_MADE. Never includes an amount. */
@Schema(description = "\"Bisi gave 🙏\", or \"Someone gave 🙏\" when anonymous")
public record GiftMade(
    @Schema(description = "Null when anonymous", example = "Bisi", nullable = true) String displayName,
    boolean anonymous
) {
}
