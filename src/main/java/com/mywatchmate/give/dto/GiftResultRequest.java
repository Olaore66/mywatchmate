package com.mywatchmate.give.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

@Schema(description = "What Payaza's checkout said when it closed. Send amount and currency together, or neither.")
public record GiftResultRequest(
    @NotNull Outcome status,
    @Schema(description = "In minor units (kobo, cents): 500000 = NGN 5,000", example = "500000", nullable = true)
    @Positive Long amountMinor,
    @Schema(example = "NGN", nullable = true) @Pattern(regexp = "[A-Z]{3}", message = "must be a 3-letter currency code")
    String currency,
    @Schema(description = "Payaza's transaction reference", nullable = true) @Size(max = 128) String providerTxnRef
) {
    public enum Outcome { COMPLETED, FAILED, CANCELLED }
}
