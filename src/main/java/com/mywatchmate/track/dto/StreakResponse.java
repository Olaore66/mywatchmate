package com.mywatchmate.track.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;

@Schema(description = """
    Days in a row with at least one counted session, in the user's timezone. Days with nothing on in any
    of their rooms don't break it; a missed session does, unless they attended another one that day.""")
public record StreakResponse(
    @Schema(example = "5") int current,
    @Schema(description = "Personal best", example = "12") int longest,
    @Schema(description = "In the user's timezone", nullable = true, example = "2026-11-06") LocalDate lastAttendedOn,
    @Schema(description = "Counted sessions in the last 7 days", example = "4") long countedThisWeek,
    @Schema(example = "Africa/Lagos") String timezone
) {
}
