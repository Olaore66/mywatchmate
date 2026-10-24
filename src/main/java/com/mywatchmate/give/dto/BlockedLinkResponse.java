package com.mywatchmate.give.dto;

import com.mywatchmate.give.SpaceBlockedLink;
import com.mywatchmate.watch.Participant;
import com.mywatchmate.watch.dto.PersonRef;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

@Schema(description = "A payment link blocked in this room")
public record BlockedLinkResponse(
    UUID paymentLinkId,
    String url,
    @Schema(nullable = true) String reason,
    @Schema(nullable = true) PersonRef blockedBy,
    Instant blockedAt
) {
    public static BlockedLinkResponse from(SpaceBlockedLink b) {
        Participant by = b.getBlockedBy();
        return new BlockedLinkResponse(b.getPaymentLink().getId(), b.getPaymentLink().getNormalizedUrl(), b.getReason(),
            by == null ? null : new PersonRef(by.getId(), by.displayName()), b.getBlockedAt());
    }
}
