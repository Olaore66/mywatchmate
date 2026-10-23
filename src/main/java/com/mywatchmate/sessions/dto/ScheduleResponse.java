package com.mywatchmate.sessions.dto;

import com.mywatchmate.watch.dto.ContentResponse;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Schema(description = "A room's repeating plan")
public record ScheduleResponse(
    UUID id,
    UUID spaceId,
    @Schema(example = "Morning Devotion", nullable = true) String title,
    @Schema(example = "[\"MONDAY\",\"TUESDAY\",\"WEDNESDAY\",\"THURSDAY\",\"FRIDAY\"]") List<DayOfWeek> days,
    @Schema(example = "06:00") String time,
    @Schema(example = "Africa/Lagos") String timezone,
    LocalDate startsOn,
    @Schema(nullable = true) LocalDate endsOn,
    @Schema(description = "False once deleted") boolean active,
    @Schema(nullable = true) ContentResponse content,
    @Schema(description = "When the next sitting starts (UTC)", nullable = true) Instant nextSessionAt
) {
}
