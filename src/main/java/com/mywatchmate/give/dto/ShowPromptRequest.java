package com.mywatchmate.give.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ShowPromptRequest(
    @Schema(description = "A Payaza payment link (e.g. the church's giving link)", example = "https://pay.payaza.africa/give/stmarks")
    @NotBlank @Size(max = 2048) String url
) {
}
