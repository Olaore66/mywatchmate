package com.mywatchmate.track;

import com.mywatchmate.auth.AuthPrincipal;
import com.mywatchmate.config.OpenApiConfig;
import com.mywatchmate.track.dto.MyAttendanceResponse;
import com.mywatchmate.track.dto.StreakResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/me")
@RequiredArgsConstructor
@SecurityRequirement(name = OpenApiConfig.BEARER)
@Tag(name = "Streaks", description = """
    My streak and attendance across all my rooms (signed-in users; guests have no streak, but keep their
    attendance when they create an account). The streak counts **days** in my timezone with at least one
    counted session; days with nothing on don't break it, a missed session does (unless I attended another
    that day). It updates the moment a session ends. `myStreak` is also in `GET /spaces/{id}`.""")
@ApiResponse(responseCode = "401", description = "UNAUTHENTICATED",
    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
@ApiResponse(responseCode = "403", description = "FORBIDDEN (guests)",
    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
public class StreakController {

    private final StreakService streaks;
    private final AttendanceService attendance;

    @GetMapping("/streak")
    @Operation(summary = "My streak")
    @ApiResponse(responseCode = "200", description = "Streak")
    public StreakResponse streak(@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal) {
        return streaks.mine(principal.userId());
    }

    @GetMapping("/attendance")
    @Operation(summary = "My attendance history", description = "Sessions that started in [from, to) "
        + "(default: the last 30 days), newest first, across all my rooms.")
    @ApiResponse(responseCode = "200", description = "History")
    @ApiResponse(responseCode = "400", description = "INVALID_RANGE",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public List<MyAttendanceResponse> history(@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
                                              @RequestParam(required = false) Instant from,
                                              @RequestParam(required = false) Instant to) {
        return attendance.mine(principal.userId(), from, to);
    }
}
