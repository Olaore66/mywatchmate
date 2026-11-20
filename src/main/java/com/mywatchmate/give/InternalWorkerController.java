package com.mywatchmate.give;

import com.mywatchmate.give.dto.LiveSessionFeed;
import com.mywatchmate.give.dto.WatchTokenResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** For the AI cue worker (X-Service-Token), not the apps: what to watch, and a way to watch a shared screen. */
@RestController
@RequestMapping("/api/v1/internal")
@RequiredArgsConstructor
@Tag(name = "Internal", description = "Used by MyWatchMate's own services, not the apps.")
public class InternalWorkerController {

    private final ServiceToken serviceToken;
    private final WorkerFeedService feed;

    @GetMapping("/live-sessions")
    @Operation(summary = "Live sessions to watch (AI worker)", description = """
        Every LIVE session with what it's playing. `streamUrl` is set only for direct streams the worker may read
        (URL, LIVESTREAM); for YouTube and Vimeo the worker watches the room's call instead when `screenShare` is true.
        Poll every ~15 s.""")
    @ApiResponse(responseCode = "200", description = "Live now")
    @ApiResponse(responseCode = "401", description = "INVALID_SERVICE_TOKEN",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "INTERNAL_DISABLED",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public List<LiveSessionFeed> liveSessions(
            @Parameter(hidden = true) @RequestHeader(value = ServiceToken.HEADER, required = false) String token) {
        serviceToken.require(token);
        return feed.liveSessions();
    }

    @PostMapping("/sessions/{sessionId}/watch-token")
    @Operation(summary = "A hidden, view-only call token for a live session (AI worker)", description = """
        Joins the room's LiveKit call as `cue-worker`: it can only receive (no camera, mic or data), and it's hidden, so
        it never appears in the room's participant list. Used to read a shared screen.""")
    @ApiResponse(responseCode = "200", description = "Token")
    @ApiResponse(responseCode = "401", description = "INVALID_SERVICE_TOKEN",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "SESSION_NOT_LIVE",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "INTERNAL_DISABLED or CALLS_DISABLED",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public WatchTokenResponse watchToken(
            @Parameter(hidden = true) @RequestHeader(value = ServiceToken.HEADER, required = false) String token,
            @PathVariable UUID sessionId) {
        serviceToken.require(token);
        return feed.watchToken(sessionId);
    }
}
