package com.mywatchmate.church.dto;

import com.mywatchmate.give.PayazaClient;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "What Payaza says about a staff test payment")
public record TestCheckoutResult(
    @Schema(description = "COMPLETED, FAILED, PENDING (not finished yet) or NOT_FOUND") PayazaClient.Outcome outcome,
    @Schema(nullable = true) Long amountMinor,
    @Schema(nullable = true) String currency,
    @Schema(description = "Payaza's reference: the church can find the payment with it", nullable = true)
    String payazaReference
) {
}
