package com.mywatchmate.church.dto;

import com.mywatchmate.church.ChurchChange;
import com.mywatchmate.give.PayazaAccount;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Schema(description = "A requested change to a church (null fields: unchanged)")
public record ChurchChangeResponse(
    UUID id,
    UUID churchId,
    @Schema(nullable = true) String publicKey,
    @Schema(nullable = true) PayazaAccount.Mode mode,
    @Schema(nullable = true) List<String> paymentLinks,
    ChurchChange.Status status,
    Instant requestedAt
) {
    public static ChurchChangeResponse from(ChurchChange c) {
        return new ChurchChangeResponse(c.getId(), c.getChurch().getId(),
            c.getPublicKey() == null ? null : PayazaAccount.mask(c.getPublicKey()), c.getMode(), c.getPaymentLinks(),
            c.getStatus(), c.getRequestedAt());
    }
}
