package com.mywatchmate.watch;

import com.mywatchmate.auth.AuthPrincipal;
import com.mywatchmate.config.OpenApiConfig;
import com.mywatchmate.watch.dto.AddContentRequest;
import com.mywatchmate.watch.dto.ContentResponse;
import com.mywatchmate.watch.dto.LinkPreviewRequest;
import com.mywatchmate.watch.dto.LinkPreviewResponse;
import com.mywatchmate.watch.dto.NowPlayingRequest;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@SecurityRequirement(name = OpenApiConfig.BEARER)
@Tag(name = "Content", description = """
    Videos in a room. Paste a link → it becomes **now playing** for everyone (NOW_PLAYING_CHANGED event).

    Supported: YouTube (watch, youtu.be, shorts, live, embed), Vimeo, and direct https `.mp4`/`.webm`/`.m3u8`.
    Not supported (their players can't be synced): Facebook, Instagram, TikTok, X, other web pages.

    **Autoplay:** browsers block autoplay with sound until the person has tapped the page. Show a
    "Tap to watch together" button when entering a room; after that tap, new videos can autoplay.""")
@ApiResponse(responseCode = "401", description = "UNAUTHENTICATED",
    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
public class ContentController {

    private final ContentService content;

    @PostMapping("/content/preview")
    @Operation(summary = "What is this link?", description = "Call as soon as a link is pasted to show a preview card "
        + "(title, thumbnail) or an error, before adding it anywhere.")
    @ApiResponse(responseCode = "200", description = "Playable link")
    @ApiResponse(responseCode = "422", description = "UNSUPPORTED_LINK, VIDEO_NOT_FOUND or VIDEO_NOT_EMBEDDABLE",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public LinkPreviewResponse preview(@Valid @RequestBody LinkPreviewRequest request) {
        return content.preview(request.url());
    }

    @GetMapping("/spaces/{spaceId}/content")
    @Operation(summary = "The room's videos", description = "Newest first.")
    @ApiResponse(responseCode = "200", description = "Videos")
    @ApiResponse(responseCode = "404", description = "SPACE_NOT_FOUND",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public List<ContentResponse> list(@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
                                      @PathVariable UUID spaceId) {
        return content.list(principal, spaceId);
    }

    @PostMapping("/spaces/{spaceId}/content")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Add a video (and play it for everyone)", description = "`playNow` defaults to true. "
        + "Members get CONTENT_ADDED, and NOW_PLAYING_CHANGED when it starts playing.")
    @ApiResponse(responseCode = "201", description = "Video added")
    @ApiResponse(responseCode = "403", description = "FORBIDDEN_ROLE: guests, or members when `membersCanAddContent` is off",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "SPACE_NOT_FOUND",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "SPACE_ARCHIVED",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "422", description = "UNSUPPORTED_LINK, VIDEO_NOT_FOUND or VIDEO_NOT_EMBEDDABLE",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public ContentResponse add(@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
                               @PathVariable UUID spaceId, @Valid @RequestBody AddContentRequest request) {
        return content.add(principal, spaceId, request);
    }

    @PutMapping("/spaces/{spaceId}/now-playing")
    @Operation(summary = "Switch to a video already in the room", description = "Members get NOW_PLAYING_CHANGED.")
    @ApiResponse(responseCode = "200", description = "Now playing")
    @ApiResponse(responseCode = "403", description = "FORBIDDEN_ROLE",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "SPACE_NOT_FOUND or CONTENT_NOT_FOUND",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public ContentResponse playNow(@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
                                   @PathVariable UUID spaceId, @Valid @RequestBody NowPlayingRequest request) {
        return content.playNow(principal, spaceId, request.contentId());
    }

    @DeleteMapping("/spaces/{spaceId}/now-playing")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Stop playing", description = "Members get NOW_PLAYING_CHANGED with data null.")
    @ApiResponse(responseCode = "204", description = "Stopped")
    @ApiResponse(responseCode = "403", description = "FORBIDDEN_ROLE",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public void stopPlaying(@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
                            @PathVariable UUID spaceId) {
        content.stopPlaying(principal, spaceId);
    }

    @DeleteMapping("/spaces/{spaceId}/content/{contentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Remove a video", description = "Whoever added it, or the owner/admins. If it was playing, "
        + "playback stops (NOW_PLAYING_CHANGED null). Members get CONTENT_REMOVED.")
    @ApiResponse(responseCode = "204", description = "Removed")
    @ApiResponse(responseCode = "403", description = "FORBIDDEN_ROLE",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "SPACE_NOT_FOUND or CONTENT_NOT_FOUND",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public void remove(@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
                       @PathVariable UUID spaceId, @PathVariable UUID contentId) {
        content.remove(principal, spaceId, contentId);
    }
}
