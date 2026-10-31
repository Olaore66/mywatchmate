package com.mywatchmate.connect.call;

import com.mywatchmate.auth.AuthPrincipal;
import com.mywatchmate.config.OpenApiConfig;
import com.mywatchmate.connect.call.dto.CallJoinResponse;
import com.mywatchmate.connect.call.dto.CallRoster;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/spaces/{spaceId}/call")
@RequiredArgsConstructor
@SecurityRequirement(name = OpenApiConfig.BEARER)
@Tag(name = "Calls", description = """
    Video and voice in a room, through **LiveKit**. Video never goes through our server: ask us for a join
    token, then connect with the LiveKit SDK (web: `livekit-client`; React: `@livekit/components-react`).

    ```js
    const { url, token } = await api.post(`/spaces/${spaceId}/call/join`);
    const room = new Room({ adaptiveStream: true, dynacast: true });
    await room.connect(url, token);
    await room.localParticipant.setCameraEnabled(true);
    await room.localParticipant.setMicrophoneEnabled(true);
    // render room.remoteParticipants' camera tracks (or use <VideoConference/> / <GridLayout/>)
    ```
    Each participant's LiveKit **identity** is their `participantId` in the space, and their **name** their display
    name, so call tiles can be matched to members. Everyone in the room (guests too) can join with camera and mic.
    Who's in the call: `GET …/call`, then **CALL_UPDATED** on the room's events. Someone removed from the room is
    taken out of its call at once. 503 CALLS_NOT_CONFIGURED when the server has no LiveKit keys.""")
@ApiResponse(responseCode = "401", description = "UNAUTHENTICATED",
    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
@ApiResponse(responseCode = "404", description = "SPACE_NOT_FOUND",
    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
@ApiResponse(responseCode = "503", description = "CALLS_NOT_CONFIGURED",
    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
public class CallController {

    private final CallService calls;

    @PostMapping("/join")
    @Operation(summary = "Get a pass to join the room's call", description = "Returns the LiveKit URL and a join "
        + "token (valid 10 minutes to connect; the connection itself lasts as long as needed).")
    @ApiResponse(responseCode = "200", description = "Connect with room.connect(url, token)")
    @ApiResponse(responseCode = "409", description = "SPACE_ARCHIVED",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public CallJoinResponse join(@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
                                 @PathVariable UUID spaceId) {
        return calls.join(principal, spaceId);
    }

    @GetMapping
    @Operation(summary = "Who's in the room's call", description = "With camera / microphone / screen-share state. "
        + "Updates arrive as CALL_UPDATED.")
    @ApiResponse(responseCode = "200", description = "The call's participants")
    @ApiResponse(responseCode = "502", description = "CALL_SERVICE_UNAVAILABLE (LiveKit didn't answer)",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public CallRoster roster(@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
                             @PathVariable UUID spaceId) {
        return calls.roster(principal, spaceId);
    }
}
