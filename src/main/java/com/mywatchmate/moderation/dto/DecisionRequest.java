package com.mywatchmate.moderation.dto;

import com.mywatchmate.give.PaymentLink;
import com.mywatchmate.moderation.LinkReview;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Schema(description = "A reviewer's decision about a link")
public record DecisionRequest(
    @Schema(description = "CONFIRM_SCAM blocks it for good; CLEAR allows it again", example = "CONFIRM_SCAM")
    @NotNull LinkReview.Decision decision,
    @Schema(description = "Why. Kept for good in the link's history.", example = "Account name doesn't match the church")
    @NotBlank @Size(max = 500) String note,
    @Schema(description = "The status the reviewer was looking at. If it changed meanwhile the decision is refused "
        + "(409 LINK_STATUS_CHANGED) instead of overruling another reviewer.", example = "HELD")
    @NotNull PaymentLink.Status expectedStatus
) {
}
