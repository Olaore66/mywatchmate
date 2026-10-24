package com.mywatchmate.live;

import com.mywatchmate.auth.AuthPrincipal;
import com.mywatchmate.config.OpenApiConfig;
import com.mywatchmate.live.dto.PlaybackCommand;
import com.mywatchmate.live.dto.PlaybackResponse;
import com.mywatchmate.live.dto.PresenceResponse;
import com.mywatchmate.watch.SpaceAccess;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/spaces/{spaceId}")
@RequiredArgsConstructor
@SecurityRequirement(name = OpenApiConfig.BEARER)
@Tag(name = "Live sync", description = """
    Everyone in a room watches the same moment. The server keeps one playback state per room and
    every player follows it. The full recipe (clock offset, drift, autoplay) is in docs/realtime-events.md.

    1. Entering a room: call `GET /time` a few times to get this device's clock offset; `GET /spaces/{id}`
       (it includes `playback` and `onlineCount`); subscribe to `/topic/spaces/{id}/events`.
    2. Line the player up: if PLAYING, seek to `positionMs + (serverNow − serverTimeMs)` and play;
       otherwise seek to `positionMs` and pause.
    3. Someone presses play/pause/seek → `POST /spaces/{id}/playback`. Everyone, the presser included,
       gets **PLAYBACK_CHANGED**; apply it unless you already have a higher `version`.
    4. Every few seconds compare the player with where it should be; more than **1.5 s** off → seek.
       Never pause the room because one person is buffering; they catch up.

    Who can press: the owner and admins always; members when `membersCanControlPlayback` is on
    (on by default in COUPLE and FRIENDS rooms); guests never. A new video starts from 0 for everyone.""")
@ApiResponse(responseCode = "401", description = "UNAUTHENTICATED",
    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
public class PlaybackController {

    private final PlaybackService playback;
    private final PresenceTracker presence;
    private final SpaceAccess access;

    @GetMapping("/playback")
    @Operation(summary = "Where the room's video is", description = "Use on entering the room and after reconnecting. "
        + "Also included in `GET /spaces/{id}` as `playback`.")
    @ApiResponse(responseCode = "200", description = "Playback state")
    @ApiResponse(responseCode = "404", description = "SPACE_NOT_FOUND",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public PlaybackResponse get(@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
                                @PathVariable UUID spaceId) {
        return playback.get(principal, spaceId);
    }

    @PostMapping("/playback")
    @Operation(summary = "Play, pause or seek for everyone", description = """
        `{"action":"PLAY"}`, `{"action":"PAUSE"}` or `{"action":"SEEK","positionMs":754000}`.
        SEEK keeps playing if it was playing. PLAY on a finished video starts it again.
        Returns the new state; everyone gets it as PLAYBACK_CHANGED. At most 5 presses a second per person.""")
    @ApiResponse(responseCode = "200", description = "The new playback state")
    @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED or POSITION_REQUIRED (SEEK without positionMs)",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "403", description = "FORBIDDEN_ROLE: guests, or members when `membersCanControlPlayback` is off",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "SPACE_NOT_FOUND",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "NOTHING_PLAYING or SPACE_ARCHIVED",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "422", description = "LIVE_CANNOT_SEEK or POSITION_OUT_OF_RANGE",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "429", description = "PLAYBACK_RATE_LIMITED",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public PlaybackResponse command(@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
                                    @PathVariable UUID spaceId, @Valid @RequestBody PlaybackCommand command) {
        return playback.command(principal, spaceId, command);
    }

    @GetMapping("/presence")
    @Operation(summary = "Who has the room open right now", description = "Each person once, however many tabs or "
        + "devices. Changes arrive as PRESENCE_CHANGED with the same body.")
    @ApiResponse(responseCode = "200", description = "Online now")
    @ApiResponse(responseCode = "404", description = "SPACE_NOT_FOUND",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public PresenceResponse presence(@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
                                     @PathVariable UUID spaceId) {
        if (!access.isActiveMember(principal, spaceId)) {
            throw SpaceAccess.notFound();
        }
        return PresenceResponse.of(presence.online(spaceId));
    }
}
