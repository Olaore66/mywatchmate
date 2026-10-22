package com.mywatchmate.watch.dto;

import com.mywatchmate.watch.Participant;
import com.mywatchmate.watch.Space;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

@Schema(description = "A room in the caller's list")
public record SpaceSummaryResponse(
    UUID id,
    @Schema(example = "6am Prayer Room") String title,
    Space.AudienceType audienceType,
    @Schema(example = "OWNER") Participant.Role myRole,
    @Schema(example = "4") Long memberCount,
    @Schema(example = "Morning Prayer, Day 12", nullable = true) String nowPlayingTitle,
    @Schema(nullable = true) String nowPlayingThumbnailUrl,
    @Schema(nullable = true) Instant archivedAt,
    Instant createdAt
) {
}
