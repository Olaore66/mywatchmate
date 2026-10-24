package com.mywatchmate.track;

import com.mywatchmate.auth.AuthPrincipal;
import com.mywatchmate.config.OpenApiConfig;
import com.mywatchmate.track.dto.RoomAttendanceResponse;
import com.mywatchmate.track.dto.SessionAttendanceResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/spaces/{spaceId}")
@RequiredArgsConstructor
@SecurityRequirement(name = OpenApiConfig.BEARER)
@Tag(name = "Attendance", description = """
    Attendance is automatic: while a session is LIVE, everyone with the room open (subscribed to its
    events) builds up watch time. When it ends, each person **counts** if they watched at least half
    of it, capped at 20 minutes (a 30-minute devotion needs 15, a 2-hour film 20). The room then gets
    **ATTENDANCE_FINALISED** ("8 of 10 attended"). Nobody needs to tap "I'm here".""")
@ApiResponse(responseCode = "401", description = "UNAUTHENTICATED",
    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
public class AttendanceController {

    private final AttendanceService attendance;

    @GetMapping("/sessions/{sessionId}/attendance")
    @Operation(summary = "Who was in a session", description = "Everyone in the room (guests too) can see it. "
        + "While the session is live, `counted` is null and watch time keeps growing.")
    @ApiResponse(responseCode = "200", description = "Attendance")
    @ApiResponse(responseCode = "404", description = "SPACE_NOT_FOUND or SESSION_NOT_FOUND",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public SessionAttendanceResponse forSession(@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
                                                @PathVariable UUID spaceId, @PathVariable UUID sessionId) {
        return attendance.forSession(principal, spaceId, sessionId);
    }

    @GetMapping("/attendance")
    @Operation(summary = "The room's attendance over a period (owner and admins)", description = """
        Ended sessions that started in [from, to) (default: the last 30 days, at most a year), with how many
        came, and each current member's record. `driftingAway` = missed the last 3 sessions they were expected
        at: a gentle nudge to check in on them. There's deliberately no ranking of members.""")
    @ApiResponse(responseCode = "200", description = "Summary")
    @ApiResponse(responseCode = "400", description = "INVALID_RANGE",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "403", description = "FORBIDDEN_ROLE",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "SPACE_NOT_FOUND",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public RoomAttendanceResponse forRoom(@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
                                          @PathVariable UUID spaceId,
                                          @Parameter(example = "2026-10-01T00:00:00Z") @RequestParam(required = false) Instant from,
                                          @Parameter(example = "2026-11-01T00:00:00Z") @RequestParam(required = false) Instant to) {
        return attendance.forRoom(principal, spaceId, from, to);
    }
}
