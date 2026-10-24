package com.mywatchmate.give.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

public record ReportRequest(
    @Schema(example = "This isn't our church's account", nullable = true) @Size(max = 300) String reason
) {
}
