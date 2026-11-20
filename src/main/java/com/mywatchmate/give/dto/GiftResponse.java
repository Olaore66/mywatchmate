package com.mywatchmate.give.dto;

import com.mywatchmate.give.Gift;
import com.mywatchmate.watch.WatchSession;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

@Schema(description = "One of my gifts. In-room payments are confirmed with Payaza; others are as my app reported them.")
public record GiftResponse(
    UUID id,
    UUID spaceId,
    String spaceTitle,
    UUID sessionId,
    String url,
    @Schema(nullable = true) String merchantName,
    @Schema(description = "OPENED (checkout open), COMPLETED, FAILED, CANCELLED, or UNKNOWN (no result reported)")
    Gift.Status status,
    @Schema(nullable = true) Long amountMinor,
    @Schema(nullable = true) String currency,
    @Schema(nullable = true) String providerTxnRef,
    boolean anonymous,
    Instant openedAt,
    @Schema(nullable = true) Instant completedAt,
    @Schema(description = "As the giver's app reported it, not confirmed by Payaza") boolean selfReported,
    @Schema(description = "Paid inside the room; Payaza confirms the result (OPENED until it does)") boolean inRoomCheckout
) {
    public static GiftResponse from(Gift g) {
        WatchSession s = g.getSession();
        return new GiftResponse(g.getId(), s.getSpace().getId(), s.getSpace().getTitle(), s.getId(),
            g.getPaymentLink().getNormalizedUrl(), g.getPaymentLink().getMerchantName(), g.getStatus(),
            g.getAmountMinor(), g.getCurrency(), g.getProviderTxnRef(), g.isAnonymous(), g.getOpenedAt(),
            g.getCompletedAt(), !g.isVerified(), g.isInRoomCheckout());
    }
}
