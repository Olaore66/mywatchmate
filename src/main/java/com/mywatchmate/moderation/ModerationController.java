package com.mywatchmate.moderation;

import com.mywatchmate.auth.AuthPrincipal;
import com.mywatchmate.config.OpenApiConfig;
import com.mywatchmate.give.PaymentLink;
import com.mywatchmate.moderation.dto.BlockUrlRequest;
import com.mywatchmate.moderation.dto.DecisionRequest;
import com.mywatchmate.moderation.dto.LinkDetail;
import com.mywatchmate.moderation.dto.LinkQueuePage;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/links")
@RequiredArgsConstructor
@SecurityRequirement(name = OpenApiConfig.BEARER)
@Tag(name = "Moderation (staff)", description = """
    Review of payment links viewers reported as scams. **Staff only**: every call is 403 `STAFF_ONLY` unless the
    signed-in user has a `staffRole` (shown on `/me`; show the Review links screen only then).

    Three reports from different people **hold** a link: it isn't shown anywhere, and its prompts come down. It
    stays held until a reviewer decides: `CONFIRM_SCAM` (blocked for good) or `CLEAR` (allowed again; only new
    reports can hold it again). A blocked link can be cleared later to undo a mistake. Every decision is kept, with
    who made it and why. Staff are emailed when a link is held, with a link to `<FRONTEND_URL>/admin/links/{id}`.""")
public class ModerationController {

    private final ModerationService moderation;

    @GetMapping
    @Operation(summary = "The review queue (or the blocked list)", description = """
        `status=HELD` (default): links waiting for review, longest waiting first. `status=BLOCKED`: confirmed
        scams, most recently blocked first. Pass `nextCursor` back as `cursor` for the next page.""")
    @ApiResponse(responseCode = "200", description = "A page of links")
    @ApiResponse(responseCode = "403", description = "STAFF_ONLY", content = @Content(mediaType = "application/problem+json",
        schema = @Schema(implementation = ProblemDetail.class)))
    public LinkQueuePage queue(@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
                               @RequestParam(required = false) PaymentLink.Status status,
                               @RequestParam(required = false) String cursor,
                               @Parameter(description = "1 to 100, default 25") @RequestParam(required = false) Integer limit) {
        return moderation.queue(principal, status, cursor, limit);
    }

    @GetMapping("/{linkId}")
    @Operation(summary = "One link with its evidence", description = """
        The reports (reasons, when, and the room; never who), the rooms it was shown in and by whom (AI or host),
        Give taps and what viewers said happened, and every past decision.""")
    @ApiResponse(responseCode = "200", description = "The link and its evidence")
    @ApiResponse(responseCode = "404", description = "LINK_NOT_FOUND", content = @Content(mediaType = "application/problem+json",
        schema = @Schema(implementation = ProblemDetail.class)))
    public LinkDetail detail(@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
                             @PathVariable UUID linkId) {
        return moderation.detail(principal, linkId);
    }

    @PostMapping("/{linkId}/decision")
    @Operation(summary = "Decide: confirm as a scam, or clear", description = """
        Allowed: HELD → BLOCKED or ALLOWED; ALLOWED → BLOCKED; BLOCKED → ALLOWED. Send the status you were looking
        at as `expectedStatus`: if another reviewer decided meanwhile you get 409 `LINK_STATUS_CHANGED`; reload and
        look again. Confirming takes down any prompt still showing it. Returns the updated link.""")
    @ApiResponse(responseCode = "200", description = "Decided")
    @ApiResponse(responseCode = "409", description = "LINK_STATUS_CHANGED or DECISION_NOT_ALLOWED (e.g. clearing an allowed link)",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public LinkDetail decide(@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
                             @PathVariable UUID linkId, @Valid @RequestBody DecisionRequest request) {
        return moderation.decide(principal, linkId, request.decision(), request.note(), request.expectedStatus());
    }

    @PostMapping("/block")
    @Operation(summary = "Block a link everywhere now", description = """
        For a scam link you hear about before viewers report it (e.g. a church tells you it's being impersonated).
        Already blocked: returned unchanged.""")
    @ApiResponse(responseCode = "200", description = "Blocked")
    @ApiResponse(responseCode = "422", description = "UNSUPPORTED_PAYMENT_LINK", content = @Content(mediaType = "application/problem+json",
        schema = @Schema(implementation = ProblemDetail.class)))
    public LinkDetail block(@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
                            @Valid @RequestBody BlockUrlRequest request) {
        return moderation.block(principal, request.url(), request.note());
    }
}
