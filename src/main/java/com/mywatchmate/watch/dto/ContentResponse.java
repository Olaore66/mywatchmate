package com.mywatchmate.watch.dto;

import com.mywatchmate.watch.ContentItem;
import com.mywatchmate.watch.Participant;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

@Schema(description = "A video in a room")
public record ContentResponse(
    @Schema(example = "5c6d7e8f-9a0b-4c1d-8e2f-3a4b5c6d7e8f") UUID id,
    @Schema(description = "Which player to use", example = "YOUTUBE") ContentItem.Source source,
    @Schema(description = "Canonical link", example = "https://www.youtube.com/watch?v=dQw4w9WgXcQ") String url,
    @Schema(description = "Provider's video id (YouTube/Vimeo); null for direct files", example = "dQw4w9WgXcQ", nullable = true)
    String externalId,
    @Schema(example = "Morning Prayer, Day 12", nullable = true) String title,
    @Schema(example = "https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg", nullable = true) String thumbnailUrl,
    @Schema(description = "Null when unknown (YouTube: the player reports it) or live", nullable = true) Integer durationSeconds,
    @Schema(description = "A live stream: everyone joins at the live point, no seeking back together") boolean live,
    @Schema(nullable = true) PersonRef addedBy,
    Instant createdAt
) {
    public static ContentResponse from(ContentItem item) {
        Participant adder = item.getAddedBy();
        return new ContentResponse(item.getId(), item.getSource(), item.getExternalUrl(), item.getExternalId(),
            item.getTitle(), item.getThumbnailUrl(), item.getDurationSeconds(), item.isLive(),
            adder == null ? null : new PersonRef(adder.getId(), adder.displayName()), item.getCreatedAt());
    }
}
