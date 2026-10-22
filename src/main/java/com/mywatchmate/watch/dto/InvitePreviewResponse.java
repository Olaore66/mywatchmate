package com.mywatchmate.watch.dto;

import com.mywatchmate.watch.Space;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "What someone sees before joining from a link (no sign-in needed)")
public record InvitePreviewResponse(
    @Schema(example = "6am Prayer Room") String spaceTitle,
    @Schema(nullable = true) String description,
    Space.AudienceType audienceType,
    @Schema(example = "Ada Okafor") String ownerName,
    @Schema(example = "4") long memberCount,
    @Schema(description = "No room left: joining will fail with SPACE_FULL") boolean full,
    @Schema(description = "If false, show sign-in/up instead of 'join as guest'") boolean allowGuests,
    @Schema(nullable = true) String nowPlayingTitle,
    @Schema(nullable = true) String nowPlayingThumbnailUrl
) {
}
