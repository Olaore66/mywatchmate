package com.mywatchmate.church.dto;

import com.mywatchmate.church.Church;
import com.mywatchmate.give.PayazaAccount;
import com.mywatchmate.give.PaymentLink;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Schema(description = "A church registered with MyWatchMate. The key is shown shortened.")
public record ChurchResponse(
    UUID id,
    String name,
    @Schema(nullable = true) String website,
    @Schema(nullable = true) String officialEmail,
    @Schema(nullable = true) String officialPhone,
    @Schema(example = "PZ78-PKL…3C4D") String publicKey,
    PayazaAccount.Mode mode,
    String currencyCode,
    @Schema(description = "PENDING (waiting for staff), VERIFIED (paid in any room), REJECTED, SUSPENDED")
    Church.Status status,
    List<String> paymentLinks,
    Instant createdAt,
    Instant statusChangedAt,
    @Schema(nullable = true) Instant verifiedAt
) {
    public static ChurchResponse from(Church c) {
        return new ChurchResponse(c.getId(), c.getName(), c.getWebsite(), c.getOfficialEmail(), c.getOfficialPhone(),
            PayazaAccount.mask(c.getPublicKey()), c.getMode(), c.getCurrencyCode(), c.getStatus(),
            c.getLinks().stream().map(PaymentLink::getNormalizedUrl).sorted().toList(), c.getCreatedAt(),
            c.getStatusChangedAt(), c.getVerifiedAt());
    }
}
