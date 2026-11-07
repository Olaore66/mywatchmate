package com.mywatchmate.moderation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "A page of links. HELD: longest waiting first. BLOCKED: most recently blocked first.")
public record LinkQueuePage(
    List<LinkSummary> links,
    @Schema(description = "Pass as `cursor` for the next page; null on the last page", nullable = true) String nextCursor,
    @Schema(description = "Links waiting for review right now (for a badge)") long waiting
) {
}
