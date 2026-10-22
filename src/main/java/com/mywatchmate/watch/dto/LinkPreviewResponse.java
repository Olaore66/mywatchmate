package com.mywatchmate.watch.dto;

import com.mywatchmate.watch.ContentItem;
import com.mywatchmate.watch.content.PreparedVideo;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "What a pasted link is, before adding it to a room")
public record LinkPreviewResponse(
    @Schema(example = "YOUTUBE") ContentItem.Source source,
    @Schema(description = "Canonical link (what will be saved)", example = "https://www.youtube.com/watch?v=dQw4w9WgXcQ") String url,
    @Schema(example = "dQw4w9WgXcQ", nullable = true) String externalId,
    @Schema(example = "Morning Prayer, Day 12", nullable = true) String title,
    @Schema(nullable = true) String thumbnailUrl,
    @Schema(nullable = true) Integer durationSeconds,
    boolean live
) {
    public static LinkPreviewResponse from(PreparedVideo video) {
        return new LinkPreviewResponse(video.link().source(), video.link().canonicalUrl(), video.link().externalId(),
            video.metadata().title(), video.metadata().thumbnailUrl(), video.metadata().durationSeconds(), video.link().live());
    }
}
