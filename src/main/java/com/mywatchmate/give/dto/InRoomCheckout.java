package com.mywatchmate.give.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = """
    Open Payaza's checkout popup in the room with these (https://docs.payaza.africa/api-reference/libraries/websdk):
    `PayazaCheckout.setup({ merchant_key, connection_mode, transaction_reference, currency_code, checkout_amount,
    email_address, first_name, last_name, phone_number })`. Ask the giver for the amount and anything not filled in.""")
public record InRoomCheckout(
    @Schema(description = "merchant_key: the church's public key") String merchantKey,
    @Schema(description = "connection_mode", example = "Live") String connectionMode,
    @Schema(description = "transaction_reference: use exactly this; it's how we confirm the payment with Payaza",
        example = "MWM8F2C1A9E0B7D4C6A9F3E2D1C0B9A8F7E") String transactionReference,
    @Schema(description = "currency_code", example = "NGN") String currencyCode,
    @Schema(description = "Who the money goes to") String merchantName,
    @Schema(description = "Prefill email_address", nullable = true) String email,
    @Schema(description = "Prefill first_name", nullable = true) String firstName,
    @Schema(description = "Prefill last_name", nullable = true) String lastName
) {
}
