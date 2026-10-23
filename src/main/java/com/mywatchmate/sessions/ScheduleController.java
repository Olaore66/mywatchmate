package com.mywatchmate.sessions;

import com.mywatchmate.auth.AuthPrincipal;
import com.mywatchmate.config.OpenApiConfig;
import com.mywatchmate.sessions.dto.ScheduleRequest;
import com.mywatchmate.sessions.dto.ScheduleResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/spaces/{spaceId}/schedules")
@RequiredArgsConstructor
@SecurityRequirement(name = OpenApiConfig.BEARER)
@Tag(name = "Schedules", description = """
    Repeating plans, e.g. every weekday at 06:00 Africa/Lagos. The time is fixed in the schedule's timezone,
    so daylight-saving changes elsewhere never move it; each person sees it converted to their own time.
    Sessions are created automatically for the next 14 days. Owner/admin manage; everyone in the room can view.
    Members get SCHEDULE_UPDATED when a schedule is created, changed or deleted.""")
@ApiResponse(responseCode = "401", description = "UNAUTHENTICATED",
    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
@ApiResponse(responseCode = "404", description = "SPACE_NOT_FOUND, SCHEDULE_NOT_FOUND or CONTENT_NOT_FOUND",
    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
public class ScheduleController {

    private final ScheduleService schedules;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a schedule (owner/admin)", description = "Its sessions for the next 14 days are created at once.")
    @ApiResponse(responseCode = "201", description = "Schedule, with nextSessionAt")
    @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED, INVALID_SCHEDULE or INVALID_TIMEZONE",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "403", description = "FORBIDDEN_ROLE",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "SPACE_ARCHIVED",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public ScheduleResponse create(@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
                                   @PathVariable UUID spaceId, @Valid @RequestBody ScheduleRequest request) {
        return schedules.create(principal, spaceId, request);
    }

    @GetMapping
    @Operation(summary = "The room's schedules")
    @ApiResponse(responseCode = "200", description = "Active schedules")
    public List<ScheduleResponse> list(@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
                                       @PathVariable UUID spaceId) {
        return schedules.list(principal, spaceId);
    }

    @PutMapping("/{scheduleId}")
    @Operation(summary = "Replace a schedule (owner/admin)", description = "Send the whole plan. Upcoming sessions that "
        + "haven't started are recreated, with fresh reminders.")
    @ApiResponse(responseCode = "200", description = "Updated schedule")
    @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED, INVALID_SCHEDULE or INVALID_TIMEZONE",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "403", description = "FORBIDDEN_ROLE",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public ScheduleResponse replace(@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
                                    @PathVariable UUID spaceId, @PathVariable UUID scheduleId,
                                    @Valid @RequestBody ScheduleRequest request) {
        return schedules.replace(principal, spaceId, scheduleId, request);
    }

    @DeleteMapping("/{scheduleId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Stop a schedule (owner/admin)", description = "Upcoming sessions are cancelled; past ones are kept.")
    @ApiResponse(responseCode = "204", description = "Stopped")
    @ApiResponse(responseCode = "403", description = "FORBIDDEN_ROLE",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public void delete(@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
                       @PathVariable UUID spaceId, @PathVariable UUID scheduleId) {
        schedules.delete(principal, spaceId, scheduleId);
    }
}
