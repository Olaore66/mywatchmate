package com.mywatchmate.watch;

import com.mywatchmate.auth.AuthPrincipal;
import com.mywatchmate.config.OpenApiConfig;
import com.mywatchmate.watch.dto.CreateSpaceRequest;
import com.mywatchmate.watch.dto.InviteResponse;
import com.mywatchmate.watch.dto.SpaceResponse;
import com.mywatchmate.watch.dto.SpaceSummaryResponse;
import com.mywatchmate.watch.dto.TransferOwnershipRequest;
import com.mywatchmate.watch.dto.UpdateSpaceRequest;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/spaces")
@RequiredArgsConstructor
@SecurityRequirement(name = OpenApiConfig.BEARER)
@Tag(name = "Spaces", description = """
    Rooms where people watch together. The creator is the OWNER.

    | Action | Owner | Admin | Member | Guest |
    |---|:-:|:-:|:-:|:-:|
    | View room, members, videos | ✅ | ✅ | ✅ | ✅ |
    | See invite link | ✅ | ✅ | ✅ | ❌ |
    | Edit room, rotate invite link | ✅ | ✅ | ❌ | ❌ |
    | Add videos / change what's playing | ✅ | ✅ | if `membersCanAddContent` | ❌ |
    | Remove people | ✅ | ✅ (not owner/admins) | ❌ | ❌ |
    | Change roles, transfer ownership, archive/restore | ✅ | ❌ | ❌ | ❌ |
    | Leave | after transferring | ✅ | ✅ | ✅ |

    Not being in a room gives **404 SPACE_NOT_FOUND** (we don't reveal it exists); being in it with too
    low a role gives **403 FORBIDDEN_ROLE**. Live updates: subscribe to `/topic/spaces/{id}/events`.""")
@ApiResponse(responseCode = "401", description = "UNAUTHENTICATED",
    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
public class SpaceController {

    private final SpaceService spaces;
    private final MembershipService membership;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a room", description = "Registered users only. Include `videoUrl` to start playing a video "
        + "straight away (check it first with POST /content/preview).")
    @ApiResponse(responseCode = "201", description = "Room created; the caller is OWNER")
    @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "403", description = "FORBIDDEN: guests can't create rooms",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "422", description = "UNSUPPORTED_LINK, VIDEO_NOT_FOUND or VIDEO_NOT_EMBEDDABLE (videoUrl)",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public SpaceResponse create(@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
                                @Valid @RequestBody CreateSpaceRequest request) {
        return spaces.create(principal.userId(), request);
    }

    @GetMapping
    @Operation(summary = "My rooms", description = "Rooms I'm in, newest first. `archived=true` lists archived rooms I own.")
    @ApiResponse(responseCode = "200", description = "Rooms")
    @ApiResponse(responseCode = "403", description = "FORBIDDEN: guests have no room list",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public List<SpaceSummaryResponse> mine(@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
                                           @RequestParam(defaultValue = "false") boolean archived) {
        return spaces.listMine(principal.userId(), archived);
    }

    @GetMapping("/{spaceId}")
    @Operation(summary = "Room details", description = "Includes `nowPlaying`: start that player when the room opens.")
    @ApiResponse(responseCode = "200", description = "Room")
    @ApiResponse(responseCode = "404", description = "SPACE_NOT_FOUND",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public SpaceResponse get(@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
                             @PathVariable UUID spaceId) {
        return spaces.get(principal, spaceId);
    }

    @PatchMapping("/{spaceId}")
    @Operation(summary = "Edit room (owner/admin)", description = "Send only the fields to change. Members get SPACE_UPDATED.")
    @ApiResponse(responseCode = "200", description = "Updated room")
    @ApiResponse(responseCode = "403", description = "FORBIDDEN_ROLE",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "SPACE_NOT_FOUND",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "SPACE_ARCHIVED",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public SpaceResponse update(@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
                                @PathVariable UUID spaceId, @Valid @RequestBody UpdateSpaceRequest request) {
        return spaces.update(principal, spaceId, request);
    }

    @PostMapping("/{spaceId}/archive")
    @Operation(summary = "Archive room (owner)", description = "Hides it for members, kills the invite link, signs guests out. "
        + "Nothing is deleted; restore any time.")
    @ApiResponse(responseCode = "200", description = "Archived room")
    @ApiResponse(responseCode = "403", description = "FORBIDDEN_ROLE",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public SpaceResponse archive(@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
                                 @PathVariable UUID spaceId) {
        return spaces.archive(principal, spaceId);
    }

    @PostMapping("/{spaceId}/restore")
    @Operation(summary = "Restore an archived room (owner)")
    @ApiResponse(responseCode = "200", description = "Restored room")
    @ApiResponse(responseCode = "403", description = "FORBIDDEN_ROLE",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public SpaceResponse restore(@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
                                 @PathVariable UUID spaceId) {
        return spaces.restore(principal, spaceId);
    }

    @GetMapping("/{spaceId}/invite")
    @Operation(summary = "Get the invite link", description = "Any registered member can share it.")
    @ApiResponse(responseCode = "200", description = "Invite token and link")
    @ApiResponse(responseCode = "403", description = "FORBIDDEN_ROLE: guests can't see the link",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public InviteResponse invite(@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
                                 @PathVariable UUID spaceId) {
        return spaces.invite(principal, spaceId);
    }

    @PostMapping("/{spaceId}/invite/rotate")
    @Operation(summary = "Replace the invite link (owner/admin)",
        description = "The old link stops working immediately. Use it when a link leaks or after removing someone.")
    @ApiResponse(responseCode = "200", description = "New invite token and link")
    @ApiResponse(responseCode = "403", description = "FORBIDDEN_ROLE",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public InviteResponse rotateInvite(@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
                                       @PathVariable UUID spaceId) {
        return spaces.rotateInvite(principal, spaceId);
    }

    @PostMapping("/{spaceId}/transfer-ownership")
    @Operation(summary = "Hand the room to another member (owner)", description = "Target must be a registered member. "
        + "The previous owner becomes an admin. Members get OWNERSHIP_TRANSFERRED.")
    @ApiResponse(responseCode = "200", description = "Room, as seen by the caller (now ADMIN)")
    @ApiResponse(responseCode = "400", description = "CANNOT_TARGET_SELF or GUEST_CANNOT_BE_OWNER",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "403", description = "FORBIDDEN_ROLE",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "SPACE_NOT_FOUND or PARTICIPANT_NOT_FOUND",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public SpaceResponse transferOwnership(@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
                                           @PathVariable UUID spaceId,
                                           @Valid @RequestBody TransferOwnershipRequest request) {
        return membership.transferOwnership(principal, spaceId, request.participantId());
    }
}
