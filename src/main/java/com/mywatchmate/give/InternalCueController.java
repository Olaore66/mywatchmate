package com.mywatchmate.give;

import com.mywatchmate.common.ApiException;
import com.mywatchmate.give.dto.CueOutcome;
import com.mywatchmate.give.dto.InternalCueRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * For our own AI worker (Phase 8), not the frontend: it reports payment cues it found in a live stream.
 * Authenticated with a shared service token (X-Service-Token), not a user's access token.
 */
@RestController
@RequestMapping("/api/v1/internal")
@RequiredArgsConstructor
@Tag(name = "Internal", description = "Used by MyWatchMate's own services, not the apps.")
public class InternalCueController {

    static final String TOKEN_HEADER = "X-Service-Token";

    private final GiveService give;
    private final GiveProperties props;

    @PostMapping("/cues")
    @Operation(summary = "A payment cue found in a live session (AI worker)", description = "Goes through the same "
        + "checks as a host's link; SHOWN means the room got a Give prompt.")
    @ApiResponse(responseCode = "200", description = "What happened to it")
    @ApiResponse(responseCode = "401", description = "INVALID_SERVICE_TOKEN",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "SESSION_NOT_LIVE",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "INTERNAL_DISABLED (no service token configured)",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public CueOutcome cue(@Parameter(hidden = true) @RequestHeader(value = TOKEN_HEADER, required = false) String token,
                          @Valid @RequestBody InternalCueRequest request) {
        requireServiceToken(token);
        return give.fromWorker(request);
    }

    private void requireServiceToken(String presented) {
        String expected = props.internalToken();
        if (expected == null || expected.isBlank()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "INTERNAL_DISABLED", "Internal endpoints are turned off.");
        }
        // Constant-time, so the token can't be guessed a character at a time
        if (presented == null || !MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                presented.getBytes(StandardCharsets.UTF_8))) {
            throw ApiException.unauthorized("INVALID_SERVICE_TOKEN", "Missing or wrong service token.");
        }
    }
}
