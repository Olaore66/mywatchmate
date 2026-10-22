package com.mywatchmate.watch.dto;

import com.mywatchmate.watch.Participant;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

public record ChangeRoleRequest(
    @Schema(description = "ADMIN or MEMBER (use transfer-ownership to change the owner)", example = "ADMIN")
    @NotNull Participant.Role role
) {
}
