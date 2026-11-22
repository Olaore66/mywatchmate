package com.mywatchmate.church;

import com.mywatchmate.auth.AuthPrincipal;
import com.mywatchmate.church.dto.ChurchApplicationRequest;
import com.mywatchmate.church.dto.ChurchChangeRequest;
import com.mywatchmate.church.dto.ChurchChangeResponse;
import com.mywatchmate.church.dto.ChurchResponse;
import com.mywatchmate.config.OpenApiConfig;
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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@SecurityRequirement(name = OpenApiConfig.BEARER)
@Tag(name = "Churches", description = """
    A church registers with MyWatchMate once, with its Payaza PUBLIC key and its own payment links. After staff verify
    it's really that church, its links are paid INSIDE any room (Payaza's popup), with a "✓ Verified" badge on the Give
    card, and every payment is confirmed with Payaza. Viewers and room creators never give us anything.
    Signed-in accounts only.""")
public class ChurchController {

    private final ChurchService churches;

    @PostMapping("/churches/applications")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Apply to register a church", description = """
        Status starts PENDING. MyWatchMate staff then contact the church through its official channels and make a small
        test payment to confirm the Payaza key is the church's own. Nothing is paid in rooms until it's VERIFIED.
        Any signed-in user can apply for a church (usually someone from the church); staff don't add churches.
        A payment link belongs to one church at a time: if another church (pending, verified or suspended) already has
        it, you get 409 `LINK_CLAIMED`, with the link in the message. A rejected church frees its links.""")
    @ApiResponse(responseCode = "201", description = "Application received")
    @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "LINK_CLAIMED (another church has that link), LINK_BLOCKED or TOO_MANY_APPLICATIONS",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "422", description = "UNSUPPORTED_PAYMENT_LINK",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public ChurchResponse apply(@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
                                @Valid @RequestBody ChurchApplicationRequest request) {
        return churches.apply(principal, request);
    }

    @GetMapping("/me/church-applications")
    @Operation(summary = "Churches I applied for, and their status")
    @ApiResponse(responseCode = "200", description = "Newest first")
    public List<ChurchResponse> mine(@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal) {
        return churches.mine(principal.userId());
    }

    @PostMapping("/churches/{churchId}/change-requests")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Ask to change a verified church's key or links", description = """
        Only whoever applied for the church. The church keeps its current key and links until staff approve this.""")
    @ApiResponse(responseCode = "201", description = "Waiting for review")
    @ApiResponse(responseCode = "404", description = "CHURCH_NOT_FOUND (or not yours)",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "CHANGE_PENDING, CHURCH_NOT_VERIFIED, LINK_CLAIMED or LINK_BLOCKED",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public ChurchChangeResponse requestChange(@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
                                              @PathVariable UUID churchId, @Valid @RequestBody ChurchChangeRequest request) {
        return churches.requestChange(principal, churchId, request);
    }
}
