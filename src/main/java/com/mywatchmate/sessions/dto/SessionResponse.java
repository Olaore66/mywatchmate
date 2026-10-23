package com.mywatchmate.sessions.dto;

import com.mywatchmate.watch.WatchSession;
import com.mywatchmate.watch.dto.ContentResponse;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;

@Schema(description = "One sitting in a room")
public record SessionResponse(
    UUID id,
    UUID spaceId,
    @Schema(example = "6am Prayer Room") String spaceTitle,
    @Schema(description = "Null for one-off and watch-now sessions", nullable = true) UUID scheduleId,
    @Schema(example = "Morning Devotion", nullable = true) String title,
    @Schema(description = "SCHEDULED → LIVE → ENDED, or CANCELLED. ENDED with no startedAt = missed.") WatchSession.Status status,
    @Schema(description = "Start time in UTC", example = "2026-11-03T05:00:00Z") Instant scheduledAt,
    @Schema(description = "Same moment in the caller's timezone (UTC for guests)", example = "2026-11-03T06:00:00+01:00")
    OffsetDateTime scheduledAtLocal,
    @Schema(description = "Timezone used for scheduledAtLocal", example = "Africa/Lagos") String localTimezone,
    @Schema(nullable = true) Instant startedAt,
    @Schema(nullable = true) Instant endedAt,
    @Schema(description = "What will play (or played)", nullable = true) ContentResponse content
) {
}
