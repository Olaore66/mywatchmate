package com.mywatchmate.church.dto;

import com.mywatchmate.give.PayazaAccount;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

@Schema(description = "A church applying to be verified on MyWatchMate")
public record ChurchApplicationRequest(
    @Schema(example = "Streams of Joy International") @NotBlank @Size(max = 200) String name,
    @Schema(description = "Staff contact the church through its official channels, so give real ones",
        example = "https://www.streamsofjoy.org", nullable = true)
    @Size(max = 500) @Pattern(regexp = "https://\\S+", message = "must be an https address") String website,
    @Schema(example = "info@streamsofjoy.org", nullable = true) @Email @Size(max = 320) String officialEmail,
    @Schema(example = "+2348012345678", nullable = true) @Size(max = 40) String officialPhone,
    @Schema(description = "The PUBLIC key from Payaza's dashboard (Settings → Developers). Never the secret key.",
        example = "PZ78-PKLIVE-1A2B3C4D5E6F")
    @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{8,200}", message = "doesn't look like a Payaza public key") String publicKey,
    @Schema(example = "LIVE") @NotNull PayazaAccount.Mode mode,
    @Schema(description = "Default NGN", example = "NGN", nullable = true)
    @Pattern(regexp = "[A-Z]{3}", message = "must be a 3-letter currency code") String currencyCode,
    @Schema(description = "The church's own Payaza payment links (1 to 10)",
        example = "[\"https://business.payaza.africa/pay/nsppd\"]")
    @NotEmpty @Size(max = 10) List<@NotBlank @Size(max = 2048) String> paymentLinks
) {
}
