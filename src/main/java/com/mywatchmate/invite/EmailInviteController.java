package com.mywatchmate.invite;

import com.mywatchmate.auth.AuthPrincipal;
import com.mywatchmate.config.OpenApiConfig;
import com.mywatchmate.invite.dto.EmailInviteRequest;
import com.mywatchmate.invite.dto.EmailInviteResponse;
import com.mywatchmate.invite.dto.InvitationResponse;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/spaces/{spaceId}/invites/email")
@RequiredArgsConstructor
@SecurityRequirement(name = OpenApiConfig.BEARER)
@Tag(name = "Invites", description = """
    **Email invitations** (owner and admins): type addresses, we email each one the room's invite link with a
    Join button (`<FRONTEND_URL>/join/<inviteToken>`, the same join flow as a shared link).

    - On **Create Space**, call it right after `POST /spaces` succeeds; on **Space Home**, whenever people are added.
    - The call returns at once with a result per address; emails go out within a minute, in the background.
    - Up to 20 addresses per call, 100 invitations per room per day. The note is plain text, links removed.
    - `GET` lists everyone invited (PENDING, SENT, JOINED, FAILED, CANCELLED, EXPIRED); someone who joins
      the room with that email address becomes JOINED. Resend sent/failed/expired ones; cancel waiting ones.""")
@ApiResponse(responseCode = "401", description = "UNAUTHENTICATED",
    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
@ApiResponse(responseCode = "403", description = "FORBIDDEN_ROLE (owner and admins only)",
    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
@ApiResponse(responseCode = "404", description = "SPACE_NOT_FOUND",
    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
public class EmailInviteController {

    private final EmailInviteService invites;

    @PostMapping
    @Operation(summary = "Invite people by email", description = """
        Per address: QUEUED (will be emailed), ALREADY_MEMBER, ALREADY_INVITED (use resend), INVALID_EMAIL, or
        RATE_LIMITED (the room's daily limit). Repeated addresses count once.""")
    @ApiResponse(responseCode = "200", description = "One result per address")
    @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED (no addresses, more than 20, or a note over 500 characters)",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "SPACE_ARCHIVED",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public EmailInviteResponse invite(@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
                                      @PathVariable UUID spaceId, @Valid @RequestBody EmailInviteRequest request) {
        return invites.invite(principal, spaceId, request);
    }

    @GetMapping
    @Operation(summary = "Everyone invited by email", description = "Newest first, for the room's invited list.")
    @ApiResponse(responseCode = "200", description = "Invitations")
    public List<InvitationResponse> list(@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
                                         @PathVariable UUID spaceId) {
        return invites.list(principal, spaceId);
    }

    @PostMapping("/{invitationId}/resend")
    @Operation(summary = "Send an invitation again", description = "Sent, failed or expired ones; at most every 10 minutes.")
    @ApiResponse(responseCode = "200", description = "Queued again")
    @ApiResponse(responseCode = "404", description = "INVITATION_NOT_FOUND",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "INVITATION_CLOSED (joined or cancelled), RESEND_TOO_SOON, "
        + "ALREADY_MEMBER, ALREADY_INVITED or SPACE_ARCHIVED",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "429", description = "INVITE_LIMIT_REACHED",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public InvitationResponse resend(@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
                                     @PathVariable UUID spaceId, @PathVariable UUID invitationId) {
        return invites.resend(principal, spaceId, invitationId);
    }

    @DeleteMapping("/{invitationId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Cancel an invitation", description = "It won't be sent or shown as pending. "
        + "The room's invite link itself keeps working; rotate it to stop that.")
    @ApiResponse(responseCode = "204", description = "Cancelled (or already closed)")
    @ApiResponse(responseCode = "404", description = "INVITATION_NOT_FOUND",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public void cancel(@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
                       @PathVariable UUID spaceId, @PathVariable UUID invitationId) {
        invites.cancel(principal, spaceId, invitationId);
    }
}
