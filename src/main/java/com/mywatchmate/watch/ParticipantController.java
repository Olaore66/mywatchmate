package com.mywatchmate.watch;

import com.mywatchmate.auth.AuthPrincipal;
import com.mywatchmate.config.OpenApiConfig;
import com.mywatchmate.watch.dto.ChangeRoleRequest;
import com.mywatchmate.watch.dto.ParticipantResponse;
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
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/spaces/{spaceId}")
@RequiredArgsConstructor
@SecurityRequirement(name = OpenApiConfig.BEARER)
@Tag(name = "Participants", description = "Who is in a room. See the permission table under Spaces.")
@ApiResponse(responseCode = "401", description = "UNAUTHENTICATED",
    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
@ApiResponse(responseCode = "404", description = "SPACE_NOT_FOUND (or PARTICIPANT_NOT_FOUND for a target)",
    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
public class ParticipantController {

    private final MembershipService membership;

    @GetMapping("/participants")
    @Operation(summary = "Everyone in the room now", description = "Oldest first; includes guests.")
    @ApiResponse(responseCode = "200", description = "Participants")
    public List<ParticipantResponse> list(@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
                                          @PathVariable UUID spaceId) {
        return membership.list(principal, spaceId);
    }

    @PatchMapping("/participants/{participantId}")
    @Operation(summary = "Make someone an admin, or a member again (owner)", description = "Members get ROLE_CHANGED.")
    @ApiResponse(responseCode = "200", description = "Updated participant")
    @ApiResponse(responseCode = "400", description = "INVALID_ROLE, CANNOT_TARGET_SELF or GUEST_CANNOT_BE_ADMIN",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "403", description = "FORBIDDEN_ROLE",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public ParticipantResponse changeRole(@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
                                          @PathVariable UUID spaceId, @PathVariable UUID participantId,
                                          @Valid @RequestBody ChangeRoleRequest request) {
        return membership.changeRole(principal, spaceId, participantId, request.role());
    }

    @DeleteMapping("/participants/{participantId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Remove someone (owner/admin)", description = "They can't rejoin from the same link. Guests can, "
        + "as a new guest, so consider rotating the invite link afterwards. Members get MEMBER_REMOVED.")
    @ApiResponse(responseCode = "204", description = "Removed")
    @ApiResponse(responseCode = "400", description = "CANNOT_TARGET_SELF (use leave)",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "403", description = "FORBIDDEN_ROLE: not owner/admin, or an admin targeting the owner/an admin",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public void remove(@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
                       @PathVariable UUID spaceId, @PathVariable UUID participantId) {
        membership.remove(principal, spaceId, participantId);
    }

    @PostMapping("/leave")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Leave the room", description = "Users can rejoin later from the link. A guest's session ends. "
        + "Members get MEMBER_LEFT.")
    @ApiResponse(responseCode = "204", description = "Left")
    @ApiResponse(responseCode = "409", description = "OWNER_MUST_TRANSFER",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public void leave(@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal, @PathVariable UUID spaceId) {
        membership.leave(principal, spaceId);
    }
}
