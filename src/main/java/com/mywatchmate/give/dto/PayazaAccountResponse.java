package com.mywatchmate.give.dto;

import com.mywatchmate.give.PayazaAccount;
import com.mywatchmate.give.PaymentLink;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

@Schema(description = "The room's Payaza account (owner and admins). The key is shown shortened.")
public record PayazaAccountResponse(
    @Schema(example = "PZ78-PKL…3C4D") String publicKey,
    PayazaAccount.Mode mode,
    String merchantName,
    @Schema(example = "NGN") String currencyCode,
    List<String> paymentLinks,
    Instant updatedAt
) {
    public static PayazaAccountResponse from(PayazaAccount account) {
        return new PayazaAccountResponse(account.maskedKey(), account.getMode(), account.getMerchantName(),
            account.getCurrencyCode(), account.getLinks().stream().map(PaymentLink::getNormalizedUrl).sorted().toList(),
            account.getUpdatedAt());
    }
}
