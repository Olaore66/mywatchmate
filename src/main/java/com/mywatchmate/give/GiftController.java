package com.mywatchmate.give;

import com.mywatchmate.auth.AuthPrincipal;
import com.mywatchmate.config.OpenApiConfig;
import com.mywatchmate.give.dto.GiftResponse;
import com.mywatchmate.give.dto.GiftResultRequest;
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
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@SecurityRequirement(name = OpenApiConfig.BEARER)
@Tag(name = "Give")
@ApiResponse(responseCode = "401", description = "UNAUTHENTICATED",
    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
public class GiftController {

    private final GiftService gifts;

    @PostMapping("/gifts/{giftId}/result")
    @Operation(summary = "What Payaza's checkout said", description = "Call once when the checkout closes, with its "
        + "result. Only whoever opened the gift. A COMPLETED gift tells the room (GIFT_MADE, no amount). "
        + "Gifts with no result after an hour become UNKNOWN; a late result is still accepted.")
    @ApiResponse(responseCode = "200", description = "Recorded")
    @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED or AMOUNT_NEEDS_CURRENCY",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "GIFT_NOT_FOUND (not yours, or doesn't exist)",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "GIFT_ALREADY_REPORTED or DUPLICATE_REFERENCE",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public GiftResponse result(@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
                               @PathVariable UUID giftId, @Valid @RequestBody GiftResultRequest request) {
        return gifts.reportResult(principal, giftId, request);
    }

    @GetMapping("/me/gifts")
    @Operation(summary = "My giving history", description = "Across all my rooms, newest first; as a guest before "
        + "signing up too. As my app reported it (self-reported).")
    @ApiResponse(responseCode = "200", description = "Gifts")
    public List<GiftResponse> mine(@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal) {
        return gifts.mine(principal.userId());
    }
}
