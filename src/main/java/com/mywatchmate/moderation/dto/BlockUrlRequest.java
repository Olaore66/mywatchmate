package com.mywatchmate.moderation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "Block a payment link everywhere straight away, without waiting for reports")
public record BlockUrlRequest(
    @Schema(example = "https://pay.payaza.africa/give/fake-stmarks") @NotBlank @Size(max = 2048) String url,
    @Schema(example = "Impersonating St Mark's; reported by their pastor") @NotBlank @Size(max = 500) String note
) {
}
