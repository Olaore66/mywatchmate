package com.mywatchmate.watch.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record TransferOwnershipRequest(
    @Schema(description = "Participant id of a registered member (not a guest)") @NotNull UUID participantId
) {
}
