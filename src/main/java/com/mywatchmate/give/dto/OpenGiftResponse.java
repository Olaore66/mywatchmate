package com.mywatchmate.give.dto;

import com.mywatchmate.give.PaymentLink;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

@Schema(description = "Open `checkoutUrl` in the room (in-app browser on mobile, popup on web), then report the result")
public record OpenGiftResponse(
    UUID giftId,
    @Schema(example = "https://pay.payaza.africa/give/stmarks") String checkoutUrl,
    PaymentLink.Provider provider,
    @Schema(nullable = true) String merchantName,
    String notice
) {
}
