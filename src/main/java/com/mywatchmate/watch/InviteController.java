package com.mywatchmate.watch;

import com.mywatchmate.auth.AuthPrincipal;
import com.mywatchmate.config.OpenApiConfig;
import com.mywatchmate.watch.dto.InvitePreviewResponse;
import com.mywatchmate.watch.dto.SpaceResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/invites/{inviteToken}")
@RequiredArgsConstructor
@Tag(name = "Invites", description = """
    Opening an invite link (`/join/{inviteToken}` in the app):
    1. `GET /invites/{inviteToken}` (no sign-in) → show the room preview.
    2. Signed in → `POST /invites/{inviteToken}/join`. Not signed in → sign in/up, or if `allowGuests`,
       `POST /auth/guest` with the invite token and a display name.""")
@ApiResponse(responseCode = "404", description = "INVITE_NOT_FOUND: wrong, rotated, or the room is archived",
    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
public class InviteController {

    private final MembershipService membership;

    @GetMapping
    @Operation(summary = "Preview the room behind a link (public)")
    @ApiResponse(responseCode = "200", description = "Room preview")
    public InvitePreviewResponse preview(@PathVariable String inviteToken) {
        return membership.preview(inviteToken);
    }

    @PostMapping("/join")
    @SecurityRequirement(name = OpenApiConfig.BEARER)
    @Operation(summary = "Join the room (signed-in user)", description = "Safe to call again: if you're already in, "
        + "you just get the room. Leaving and rejoining makes you a MEMBER again. Members get MEMBER_JOINED.")
    @ApiResponse(responseCode = "200", description = "The room you're now in")
    @ApiResponse(responseCode = "401", description = "UNAUTHENTICATED",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "403", description = "REMOVED_FROM_SPACE, or FORBIDDEN for guest tokens",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "SPACE_FULL",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public SpaceResponse join(@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
                              @PathVariable String inviteToken) {
        return membership.join(principal.userId(), inviteToken);
    }
}
