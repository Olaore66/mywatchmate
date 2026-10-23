package com.mywatchmate.sessions.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;

@Schema(description = "A weekly plan. Used to create a schedule and to replace it (PUT sends the whole thing).")
public record ScheduleRequest(
    @Schema(example = "Morning Devotion", nullable = true) @Size(max = 150) String title,
    @Schema(example = "[\"MONDAY\",\"TUESDAY\",\"WEDNESDAY\",\"THURSDAY\",\"FRIDAY\"]") @NotEmpty Set<DayOfWeek> days,
    @Schema(description = "24-hour HH:mm in the schedule's timezone", example = "06:00")
    @NotBlank @Pattern(regexp = "([01]\\d|2[0-3]):[0-5]\\d", message = "must be HH:mm, e.g. 06:00") String time,
    @Schema(description = "IANA timezone the time is in", example = "Africa/Lagos") @NotBlank @Size(max = 64) String timezone,
    @Schema(description = "First day it can happen", example = "2026-11-02") @NotNull LocalDate startsOn,
    @Schema(description = "Last day it can happen; omit for no end", example = "2026-12-24", nullable = true) LocalDate endsOn,
    @Schema(description = "A video in this room to play each time; omit to use whatever is playing", nullable = true)
    UUID contentId
) {
}
