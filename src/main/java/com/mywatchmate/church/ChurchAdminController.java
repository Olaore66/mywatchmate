package com.mywatchmate.church;

import com.mywatchmate.auth.AuthPrincipal;
import com.mywatchmate.church.dto.ChangeDecisionRequest;
import com.mywatchmate.church.dto.ChurchDecisionRequest;
import com.mywatchmate.church.dto.ChurchDetail;
import com.mywatchmate.church.dto.ChurchQueue;
import com.mywatchmate.church.dto.TestCheckoutResult;
import com.mywatchmate.config.OpenApiConfig;
import com.mywatchmate.give.dto.InRoomCheckout;
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
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
@SecurityRequirement(name = OpenApiConfig.BEARER)
@Tag(name = "Churches (staff)", description = """
    Staff verify churches before their payment links are paid inside rooms. **Staff only** (403 STAFF_ONLY otherwise).
    Approving decides where money goes in every room, so follow `checklist` on each church: contact it through channels
    you found yourself, and make a ₦100 test payment (`/test-checkout`) to see whose Payaza account the key belongs to.
    Every decision is kept for good.

    **Roles today:** any staff role (`MODERATOR` or `ADMIN`) can use every staff endpoint; the server doesn't yet tell
    them apart. Show staff screens when `GET /me` has a `staffRole`. (Admin-only actions are coming with the admin area.)""")
@ApiResponse(responseCode = "403", description = "STAFF_ONLY", content = @Content(mediaType = "application/problem+json",
    schema = @Schema(implementation = ProblemDetail.class)))
public class ChurchAdminController {

    private final ChurchReviewService review;

    @GetMapping("/churches")
    @Operation(summary = "Churches by status (default PENDING, oldest first)", description = "Up to 200, plus counts of what's waiting.")
    @ApiResponse(responseCode = "200", description = "The churches in that status")
    public ChurchQueue list(@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
                            @RequestParam(required = false) Church.Status status) {
        return review.list(principal, status);
    }

    @GetMapping("/churches/{churchId}")
    @Operation(summary = "A church: details, applicant, pending change, gifts, history and the checklist")
    @ApiResponse(responseCode = "200", description = "The church and everything needed to decide")
    @ApiResponse(responseCode = "404", description = "CHURCH_NOT_FOUND",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public ChurchDetail detail(@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
                               @PathVariable UUID churchId) {
        return review.detail(principal, churchId);
    }

    @PostMapping("/churches/{churchId}/decision")
    @Operation(summary = "Approve, reject, suspend or reinstate", description = """
        APPROVE/REJECT a PENDING church; SUSPEND a VERIFIED one (its links go back to opening in a new tab at once);
        REINSTATE a SUSPENDED one. Rejecting frees its links for the real church. Send the status you were looking at
        as `expectedStatus`. Returns the church as it is now (same as GET), with the new decision first in `history`.""")
    @ApiResponse(responseCode = "200", description = "Decided: the updated church")
    @ApiResponse(responseCode = "409", description = "CHURCH_STATUS_CHANGED or DECISION_NOT_ALLOWED",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public ChurchDetail decide(@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
                               @PathVariable UUID churchId, @Valid @RequestBody ChurchDecisionRequest request) {
        return review.decide(principal, churchId, request);
    }

    @PostMapping("/church-changes/{changeId}/decision")
    @Operation(summary = "Approve or reject a church's new key or links", description = "Test the new key first with "
        + "`/test-checkout?pendingKey=true`. Approved changes take effect at once. Returns the church as it is now "
        + "(same as GET), with `pendingChange` null.")
    @ApiResponse(responseCode = "200", description = "Decided: the updated church")
    @ApiResponse(responseCode = "409", description = "LINK_CLAIMED (another church has one of the new links meanwhile) "
        + "or LINK_BLOCKED (one was blocked meanwhile)",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "CHANGE_NOT_FOUND (no such change, or someone already decided it)",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public ChurchDetail decideChange(@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
                                     @PathVariable UUID changeId, @Valid @RequestBody ChangeDecisionRequest request) {
        return review.decideChange(principal, changeId, request);
    }

    @PostMapping("/churches/{churchId}/test-checkout")
    @Operation(summary = "A ₦100 verification payment with the church's key", description = """
        Open Payaza's popup with this (as for a gift) and pay ₦100. The popup and receipt show the Payaza business name
        the key belongs to; it must be the church's. Then check the result with the GET below, and ask the church to
        confirm the money arrived. `pendingKey=true` tests the key in its pending change instead.""")
    @ApiResponse(responseCode = "200", description = "Open the popup with this")
    public InRoomCheckout testCheckout(@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
                                       @PathVariable UUID churchId,
                                       @RequestParam(defaultValue = "false") boolean pendingKey) {
        return review.testCheckout(principal, churchId, pendingKey);
    }

    @GetMapping("/churches/{churchId}/test-checkout/{reference}")
    @Operation(summary = "What Payaza says about a test payment")
    @ApiResponse(responseCode = "200", description = "Payaza's answer")
    public TestCheckoutResult testCheckoutResult(@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
                                                 @PathVariable UUID churchId, @PathVariable String reference,
                                                 @RequestParam(defaultValue = "false") boolean pendingKey) {
        return review.testCheckoutResult(principal, churchId, reference, pendingKey);
    }
}
