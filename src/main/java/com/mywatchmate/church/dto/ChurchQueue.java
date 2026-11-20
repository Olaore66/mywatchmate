package com.mywatchmate.church.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "Churches in one status (oldest first), plus what's waiting for staff")
public record ChurchQueue(
    List<ChurchResponse> churches,
    @Schema(description = "Applications waiting for review") long pendingApplications,
    @Schema(description = "Key or link changes waiting for review") long pendingChanges
) {
}
