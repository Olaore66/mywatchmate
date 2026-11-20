package com.mywatchmate.church.dto;

import com.mywatchmate.church.Church;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Schema(description = "A staff decision about a church")
public record ChurchDecisionRequest(
    @Schema(description = "APPROVE or REJECT a PENDING church; SUSPEND a VERIFIED one; REINSTATE a SUSPENDED one")
    @NotNull Decision decision,
    @Schema(description = "What was checked and why. Kept for good.",
        example = "Called the number on streamsofjoy.org; ₦100 test payment showed 'Streams of Joy International'")
    @NotBlank @Size(max = 1000) String note,
    @Schema(description = "The status you were looking at; if it changed meanwhile you get 409 CHURCH_STATUS_CHANGED")
    @NotNull Church.Status expectedStatus
) {
    public enum Decision { APPROVE, REJECT, SUSPEND, REINSTATE }
}
