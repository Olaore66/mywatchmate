package com.mywatchmate.give.dto;

import com.mywatchmate.give.Gift;
import com.mywatchmate.watch.WatchSession;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

@Schema(description = "One of my gifts. Results are as my app reported them; Payaza confirms to the link's owner, not us.")
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
    @Schema(description = "Always true: not verified by Payaza") boolean selfReported
) {
    public static GiftResponse from(Gift g) {
        WatchSession s = g.getSession();
        return new GiftResponse(g.getId(), s.getSpace().getId(), s.getSpace().getTitle(), s.getId(),
            g.getPaymentLink().getNormalizedUrl(), g.getPaymentLink().getMerchantName(), g.getStatus(),
            g.getAmountMinor(), g.getCurrency(), g.getProviderTxnRef(), g.isAnonymous(), g.getOpenedAt(),
            g.getCompletedAt(), true);
    }
}
