package com.mywatchmate.church.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Schema(description = "Approve or reject a church's requested change")
public record ChangeDecisionRequest(
    @NotNull Decision decision,
    @Schema(example = "New key checked with a ₦100 test payment") @NotBlank @Size(max = 1000) String note
) {
    public enum Decision { APPROVE, REJECT }
}
