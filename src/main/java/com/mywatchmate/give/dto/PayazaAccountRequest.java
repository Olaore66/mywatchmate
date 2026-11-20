package com.mywatchmate.give.dto;

import com.mywatchmate.give.PayazaAccount;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

@Schema(description = "The church's Payaza account for paying inside this room")
public record PayazaAccountRequest(
    @Schema(description = "The PUBLIC key from Payaza's dashboard (Settings → Developers). Never the secret key.",
        example = "PZ78-PKLIVE-1A2B3C4D5E6F")
    @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{8,200}", message = "doesn't look like a Payaza public key") String publicKey,
    @Schema(description = "TEST while trying it out, LIVE for real money", example = "LIVE") @NotNull PayazaAccount.Mode mode,
    @Schema(description = "Shown to givers: who the money goes to", example = "St Mark's Church") @NotBlank @Size(max = 200)
    String merchantName,
    @Schema(description = "Default NGN", example = "NGN", nullable = true)
    @Pattern(regexp = "[A-Z]{3}", message = "must be a 3-letter currency code") String currencyCode,
    @Schema(description = "The church's own Payaza payment links (1 to 10). Only these open the checkout in the room.",
        example = "[\"https://business.payaza.africa/pay/stmarks\"]")
    @NotEmpty @Size(max = 10) List<@NotBlank @Size(max = 2048) String> paymentLinks
) {
}
