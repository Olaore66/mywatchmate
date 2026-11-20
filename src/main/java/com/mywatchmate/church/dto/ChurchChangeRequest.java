package com.mywatchmate.church.dto;

import com.mywatchmate.give.PayazaAccount;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

@Schema(description = "What a verified church wants changed; leave out what stays the same. Staff review it first.")
public record ChurchChangeRequest(
    @Schema(description = "A new PUBLIC key", nullable = true)
    @Pattern(regexp = "[A-Za-z0-9_-]{8,200}", message = "doesn't look like a Payaza public key") String publicKey,
    @Schema(nullable = true) PayazaAccount.Mode mode,
    @Schema(description = "The full new list of payment links (replaces the current one)", nullable = true)
    @Size(min = 1, max = 10) List<@Size(max = 2048) String> paymentLinks
) {
    public boolean isEmpty() {
        return publicKey == null && mode == null && paymentLinks == null;
    }
}
