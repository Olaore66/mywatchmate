package com.mywatchmate.invite.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "One result per distinct address, in the order sent")
public record EmailInviteResponse(
    List<EmailInviteResult> results,
    @Schema(description = "How many will be emailed", example = "2") int queued
) {
}
