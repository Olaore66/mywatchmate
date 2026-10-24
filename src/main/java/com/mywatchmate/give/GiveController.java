package com.mywatchmate.give;

import com.mywatchmate.auth.AuthPrincipal;
import com.mywatchmate.config.OpenApiConfig;
import com.mywatchmate.give.dto.BlockLinkRequest;
import com.mywatchmate.give.dto.BlockedLinkResponse;
import com.mywatchmate.give.dto.GiveSummary;
import com.mywatchmate.give.dto.OpenGiftRequest;
import com.mywatchmate.give.dto.OpenGiftResponse;
import com.mywatchmate.give.dto.PromptResponse;
import com.mywatchmate.give.dto.ReportRequest;
import com.mywatchmate.give.dto.ShowPromptRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/spaces/{spaceId}/give")
@RequiredArgsConstructor
@SecurityRequirement(name = OpenApiConfig.BEARER)
@Tag(name = "Give", description = """
    Giving without leaving the room. During a live session a **Give prompt** appears for everyone
    (GIVE_PROMPT): spotted in the stream by the AI (Phase 8), or put up by a host with the room's Payaza link.

    1. Show the card with `notice` ("You're paying … through Payaza. MyWatchMate never holds your money.").
    2. Tap Give → `POST …/prompts/{promptId}/open` → open `checkoutUrl` **inside the room**
       (in-app browser on mobile, popup on web), so nobody leaves the stream.
    3. When the checkout closes → `POST /gifts/{giftId}/result` with what it said.
    4. The room gets GIFT_MADE ("Bisi gave 🙏", or "Someone" when anonymous). Never an amount.

    Results are **self-reported**: Payaza confirms to the link's owner, not to us. Only https Payaza links are
    ever shown. Hosts can block links in their room; 3 "looks like a scam" reports block a link everywhere.
    One prompt shows at a time; a newer one replaces it; GIVE_PROMPT_CLOSED takes it down.""")
@ApiResponse(responseCode = "401", description = "UNAUTHENTICATED",
    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
public class GiveController {

    private final GiveService give;
    private final GiftService gifts;

    @PostMapping("/prompts")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Show a Give button (owner and admins)", description = "During a live session, with the "
        + "room's Payaza payment link. Everyone gets GIVE_PROMPT; it stays up 5 minutes unless closed.")
    @ApiResponse(responseCode = "201", description = "Showing")
    @ApiResponse(responseCode = "403", description = "FORBIDDEN_ROLE",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "NO_LIVE_SESSION, LINK_BLOCKED or SPACE_ARCHIVED",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "422", description = "UNSUPPORTED_PAYMENT_LINK (not an https Payaza link)",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public PromptResponse show(@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
                               @PathVariable UUID spaceId, @Valid @RequestBody ShowPromptRequest request) {
        return give.showByHost(principal, spaceId, request.url());
    }

    @GetMapping("/prompts/current")
    @Operation(summary = "The Give prompt showing now", description = "For someone opening the room mid-session. "
        + "204 when nothing is showing.")
    @ApiResponse(responseCode = "200", description = "Showing")
    @ApiResponse(responseCode = "204", description = "Nothing showing")
    public ResponseEntity<PromptResponse> current(@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
                                                  @PathVariable UUID spaceId) {
        return give.current(principal, spaceId).map(ResponseEntity::ok).orElse(ResponseEntity.noContent().build());
    }

    @DeleteMapping("/prompts/{promptId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Take the Give button down (owner and admins)", description = "Everyone gets GIVE_PROMPT_CLOSED.")
    @ApiResponse(responseCode = "204", description = "Closed")
    @ApiResponse(responseCode = "404", description = "PROMPT_NOT_FOUND",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public void close(@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
                      @PathVariable UUID spaceId, @PathVariable UUID promptId) {
        give.close(principal, spaceId, promptId);
    }

    @PostMapping("/prompts/{promptId}/open")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Tap Give", description = "Records the gift as opened and returns the checkout to open "
        + "in the room. Guests can give too. `anonymous: true` hides your name from the room and the hosts.")
    @ApiResponse(responseCode = "201", description = "Open the checkout")
    @ApiResponse(responseCode = "404", description = "SPACE_NOT_FOUND or PROMPT_NOT_FOUND",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "PROMPT_CLOSED or LINK_BLOCKED",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public OpenGiftResponse open(@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
                                 @PathVariable UUID spaceId, @PathVariable UUID promptId,
                                 @RequestBody(required = false) OpenGiftRequest request) {
        return gifts.open(principal, spaceId, promptId, request != null && Boolean.TRUE.equals(request.anonymous()));
    }

    @PostMapping("/prompts/{promptId}/report")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "\"This looks like a scam\"", description = "Each person counts once per link. After 3 "
        + "reports from different people the link is blocked everywhere until reviewed, and taken down at once.")
    @ApiResponse(responseCode = "204", description = "Reported")
    @ApiResponse(responseCode = "404", description = "PROMPT_NOT_FOUND",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public void report(@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
                       @PathVariable UUID spaceId, @PathVariable UUID promptId,
                       @Valid @RequestBody(required = false) ReportRequest request) {
        give.report(principal, spaceId, promptId, request == null ? null : request.reason());
    }

    @GetMapping("/blocked-links")
    @Operation(summary = "Links blocked in this room (owner and admins)")
    @ApiResponse(responseCode = "200", description = "Blocked links, newest first")
    public List<BlockedLinkResponse> blocked(@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
                                             @PathVariable UUID spaceId) {
        return give.blockedLinks(principal, spaceId);
    }

    @PostMapping("/blocked-links")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Block a payment link in this room (owner and admins)", description = "It will never be "
        + "shown here, and is taken down if it's showing now.")
    @ApiResponse(responseCode = "201", description = "Blocked (or already was)")
    @ApiResponse(responseCode = "422", description = "UNSUPPORTED_PAYMENT_LINK",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public BlockedLinkResponse block(@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
                                     @PathVariable UUID spaceId, @Valid @RequestBody BlockLinkRequest request) {
        return give.block(principal, spaceId, request);
    }

    @DeleteMapping("/blocked-links/{paymentLinkId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Unblock a payment link in this room (owner and admins)")
    @ApiResponse(responseCode = "204", description = "Unblocked (or wasn't blocked)")
    public void unblock(@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
                        @PathVariable UUID spaceId, @PathVariable UUID paymentLinkId) {
        give.unblock(principal, spaceId, paymentLinkId);
    }

    @GetMapping("/summary")
    @Operation(summary = "Giving summary (owner and admins)", description = "Gifts opened in [from, to) (default: last "
        + "30 days, at most a year). Self-reported, not verified by Payaza; never shows who gave what.")
    @ApiResponse(responseCode = "200", description = "Summary")
    @ApiResponse(responseCode = "400", description = "INVALID_RANGE",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "403", description = "FORBIDDEN_ROLE",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public GiveSummary summary(@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
                               @PathVariable UUID spaceId,
                               @RequestParam(required = false) Instant from, @RequestParam(required = false) Instant to) {
        return gifts.summary(principal, spaceId, from, to);
    }
}
