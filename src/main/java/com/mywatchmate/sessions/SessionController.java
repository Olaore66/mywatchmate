package com.mywatchmate.sessions;

import com.mywatchmate.auth.AuthPrincipal;
import com.mywatchmate.config.OpenApiConfig;
import com.mywatchmate.sessions.dto.CreateSessionRequest;
import com.mywatchmate.sessions.dto.SessionResponse;
import com.mywatchmate.sessions.dto.WatchNowRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@SecurityRequirement(name = OpenApiConfig.BEARER)
@Tag(name = "Sessions", description = """
    Sittings: SCHEDULED → LIVE → ENDED (or CANCELLED). Scheduled sessions **start on their own** at their time
    and **end on their own** when the video is over (or after 3 hours if its length is unknown); hosts can also
    start, end or cancel them. At most one session is live per room. Every session has `scheduledAt` (UTC) and
    `scheduledAtLocal` in the caller's timezone. Members get SESSION_SCHEDULED / _STARTED / _ENDED / _CANCELLED.""")
@ApiResponse(responseCode = "401", description = "UNAUTHENTICATED",
    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
public class SessionController {

    private final SessionService sessions;

    @GetMapping("/spaces/{spaceId}/sessions")
    @Operation(summary = "The room's sessions", description = "Default: the live one and the next 14 days. "
        + "With `from`/`to` (ISO-8601): every session starting in that window, for history.")
    @ApiResponse(responseCode = "200", description = "Sessions, soonest first")
    @ApiResponse(responseCode = "404", description = "SPACE_NOT_FOUND",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public List<SessionResponse> list(@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
                                      @PathVariable UUID spaceId,
                                      @RequestParam(required = false) Instant from, @RequestParam(required = false) Instant to) {
        return sessions.list(principal, spaceId, from, to);
    }

    @GetMapping("/me/upcoming")
    @Operation(summary = "What's coming up in all my rooms", description = "Live now plus the next 7 days, in my timezone.")
    @ApiResponse(responseCode = "200", description = "Sessions, soonest first")
    @ApiResponse(responseCode = "403", description = "FORBIDDEN: guests",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public List<SessionResponse> upcoming(@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal) {
        return sessions.upcomingForUser(principal);
    }

    @PostMapping("/spaces/{spaceId}/sessions")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Schedule a one-off session (owner/admin)")
    @ApiResponse(responseCode = "201", description = "Session")
    @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED (scheduledAt must be in the future)",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "403", description = "FORBIDDEN_ROLE",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "SPACE_NOT_FOUND or CONTENT_NOT_FOUND",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public SessionResponse create(@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
                                  @PathVariable UUID spaceId, @Valid @RequestBody CreateSessionRequest request) {
        return sessions.createOneOff(principal, spaceId, request);
    }

    @PostMapping("/spaces/{spaceId}/sessions/watch-now")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Start watching together now", description = "Owner/admin, or members when the room lets members add "
        + "videos. Body optional. If a session is already live, join that one instead (409 SESSION_ALREADY_LIVE).")
    @ApiResponse(responseCode = "201", description = "The new live session")
    @ApiResponse(responseCode = "403", description = "FORBIDDEN_ROLE",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "SESSION_ALREADY_LIVE or SPACE_ARCHIVED",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public SessionResponse watchNow(@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
                                    @PathVariable UUID spaceId, @Valid @RequestBody(required = false) WatchNowRequest request) {
        return sessions.watchNow(principal, spaceId, request);
    }

    @PostMapping("/spaces/{spaceId}/sessions/{sessionId}/start")
    @Operation(summary = "Start a scheduled session early (owner/admin)")
    @ApiResponse(responseCode = "200", description = "Live session")
    @ApiResponse(responseCode = "409", description = "SESSION_ALREADY_LIVE, SESSION_WRONG_STATUS or SPACE_ARCHIVED",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public SessionResponse start(@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
                                 @PathVariable UUID spaceId, @PathVariable UUID sessionId) {
        return sessions.start(principal, spaceId, sessionId);
    }

    @PostMapping("/spaces/{spaceId}/sessions/{sessionId}/end")
    @Operation(summary = "End a live session (owner/admin)")
    @ApiResponse(responseCode = "200", description = "Ended session")
    @ApiResponse(responseCode = "409", description = "SESSION_WRONG_STATUS",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public SessionResponse end(@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
                               @PathVariable UUID spaceId, @PathVariable UUID sessionId) {
        return sessions.end(principal, spaceId, sessionId);
    }

    @PostMapping("/spaces/{spaceId}/sessions/{sessionId}/cancel")
    @Operation(summary = "Cancel a scheduled session (owner/admin)", description = "Its reminders are cancelled too.")
    @ApiResponse(responseCode = "200", description = "Cancelled session")
    @ApiResponse(responseCode = "409", description = "SESSION_WRONG_STATUS",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public SessionResponse cancel(@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
                                  @PathVariable UUID spaceId, @PathVariable UUID sessionId) {
        return sessions.cancel(principal, spaceId, sessionId);
    }
}
