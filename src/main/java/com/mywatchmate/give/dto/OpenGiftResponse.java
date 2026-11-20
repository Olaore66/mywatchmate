package com.mywatchmate.give.dto;

import com.mywatchmate.give.PaymentLink;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

@Schema(description = """
    If `checkout` is set, open Payaza's checkout popup in the room with it (the giver never leaves), then send
    `POST /gifts/{giftId}/result` when it closes: we confirm the result with Payaza ourselves. Otherwise open
    `checkoutUrl` in a new tab and report what the giver says happened.""")
public record OpenGiftResponse(
    UUID giftId,
    @Schema(example = "https://business.payaza.africa/pay/stmarks") String checkoutUrl,
    PaymentLink.Provider provider,
    @Schema(nullable = true) String merchantName,
    String notice,
    @Schema(description = "Pay inside the room with Payaza's popup; null when this link isn't the room's own registered link",
        nullable = true) InRoomCheckout checkout,
    @Schema(description = "A church MyWatchMate verified: show the \"✓ Verified\" badge with this name", nullable = true)
    String verifiedChurch
) {
}
