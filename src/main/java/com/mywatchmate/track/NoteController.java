package com.mywatchmate.track;

import com.mywatchmate.auth.AuthPrincipal;
import com.mywatchmate.config.OpenApiConfig;
import com.mywatchmate.track.dto.NoteRequest;
import com.mywatchmate.track.dto.NoteResponse;
import com.mywatchmate.track.dto.UpdateNoteRequest;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/spaces/{spaceId}/notes")
@RequiredArgsConstructor
@SecurityRequirement(name = OpenApiConfig.BEARER)
@Tag(name = "Notes", description = """
    Notes in a room, optionally about a session and a moment in the video (`positionSeconds`, e.g. 750 = 12:30;
    tap it to jump there).

    - **PRIVATE** (default): only the author sees it, never the owner or admins.
    - **SHARED**: the whole room sees it; they get NOTE_ADDED / NOTE_UPDATED / NOTE_DELETED live.

    Anyone in the room (guests too) can write notes. Authors edit and delete their own; the owner and
    admins may also delete shared ones. Someone else's private note is always **404 NOTE_NOT_FOUND**.""")
@ApiResponse(responseCode = "401", description = "UNAUTHENTICATED",
    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
public class NoteController {

    private final NoteService notes;

    @GetMapping
    @Operation(summary = "Notes I can see", description = "Shared notes plus my private ones, newest first. "
        + "`sessionId` for one session; `mine=true` for only mine.")
    @ApiResponse(responseCode = "200", description = "Notes")
    @ApiResponse(responseCode = "404", description = "SPACE_NOT_FOUND",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public List<NoteResponse> list(@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
                                   @PathVariable UUID spaceId,
                                   @RequestParam(required = false) UUID sessionId,
                                   @RequestParam(defaultValue = "false") boolean mine) {
        return notes.list(principal, spaceId, sessionId, mine);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Write a note")
    @ApiResponse(responseCode = "201", description = "Created")
    @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "SPACE_NOT_FOUND or SESSION_NOT_FOUND",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "SPACE_ARCHIVED",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public NoteResponse create(@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
                               @PathVariable UUID spaceId, @Valid @RequestBody NoteRequest request) {
        return notes.create(principal, spaceId, request);
    }

    @PatchMapping("/{noteId}")
    @Operation(summary = "Edit my note", description = "Changing SHARED → PRIVATE removes it from everyone else's "
        + "screen (NOTE_DELETED); PRIVATE → SHARED shows it (NOTE_ADDED).")
    @ApiResponse(responseCode = "200", description = "Updated")
    @ApiResponse(responseCode = "403", description = "NOT_YOUR_NOTE",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "SPACE_NOT_FOUND or NOTE_NOT_FOUND",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public NoteResponse update(@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
                               @PathVariable UUID spaceId, @PathVariable UUID noteId,
                               @Valid @RequestBody UpdateNoteRequest request) {
        return notes.update(principal, spaceId, noteId, request);
    }

    @DeleteMapping("/{noteId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete a note", description = "Mine; or, for owner/admins, any shared note.")
    @ApiResponse(responseCode = "204", description = "Deleted")
    @ApiResponse(responseCode = "403", description = "NOT_YOUR_NOTE",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "SPACE_NOT_FOUND or NOTE_NOT_FOUND",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public void delete(@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
                       @PathVariable UUID spaceId, @PathVariable UUID noteId) {
        notes.delete(principal, spaceId, noteId);
    }
}
