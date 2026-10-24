package com.mywatchmate.track.dto;

import com.mywatchmate.watch.WatchSession;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Schema(description = "Who was in one session")
public record SessionAttendanceResponse(
    UUID sessionId,
    WatchSession.Status status,
    @Schema(nullable = true) Instant startedAt,
    @Schema(nullable = true) Instant endedAt,
    @Schema(description = "Watch time needed to count, once the session has ended", example = "900", nullable = true)
    Integer requiredSeconds,
    @Schema(description = "People who counted (0 while live)", example = "8") int attendedCount,
    @Schema(description = "People seen at all", example = "10") int presentCount,
    @Schema(description = "Longest watchers first") List<AttendeeResponse> people
) {
}
