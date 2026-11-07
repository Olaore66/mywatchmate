package com.mywatchmate.moderation.dto;

import com.mywatchmate.give.PaymentLink;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

@Schema(description = "A payment link in the review queue (or the blocked list)")
public record LinkSummary(
    UUID id,
    @Schema(example = "https://pay.payaza.africa/give/stmarks") String url,
    PaymentLink.Provider provider,
    @Schema(nullable = true) String merchantName,
    @Schema(description = "HELD: waiting for review (not shown meanwhile). BLOCKED: confirmed scam. ALLOWED: fine")
    PaymentLink.Status status,
    @Schema(description = "Scam reports since the last review") int openReports,
    @Schema(description = "When it was held (for HELD: how long it has waited) or blocked") Instant statusChangedAt,
    Instant firstSeenAt,
    Instant lastSeenAt
) {
    public static LinkSummary from(PaymentLink link) {
        return new LinkSummary(link.getId(), link.getNormalizedUrl(), link.getProvider(), link.getMerchantName(),
            link.getStatus(), link.getReportCount(), link.getStatusChangedAt(), link.getFirstSeenAt(), link.getLastSeenAt());
    }
}
