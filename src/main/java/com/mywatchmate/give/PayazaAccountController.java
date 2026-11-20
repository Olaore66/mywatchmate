package com.mywatchmate.give;

import com.mywatchmate.auth.AuthPrincipal;
import com.mywatchmate.config.OpenApiConfig;
import com.mywatchmate.give.dto.PayazaAccountRequest;
import com.mywatchmate.give.dto.PayazaAccountResponse;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/spaces/{spaceId}/give/payaza-account")
@RequiredArgsConstructor
@SecurityRequirement(name = OpenApiConfig.BEARER)
@Tag(name = "Give", description = "Give prompts, gifts and blocks")
public class PayazaAccountController {

    private final PayazaAccountService accounts;

    @GetMapping
    @Operation(summary = "The room's Payaza account (owner and admins)", description = "204 when none is registered.")
    @ApiResponse(responseCode = "200", description = "Registered")
    @ApiResponse(responseCode = "204", description = "None registered: Give opens links in a new tab")
    public ResponseEntity<PayazaAccountResponse> get(@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
                                                     @PathVariable UUID spaceId) {
        return accounts.get(principal, spaceId).map(ResponseEntity::ok).orElse(ResponseEntity.noContent().build());
    }

    @PutMapping
    @Operation(summary = "Register the church's Payaza account: givers pay inside the room", description = """
        The PUBLIC key from Payaza's dashboard (Settings → Developers; never the secret key) and the church's own
        payment links. When one of these links is the Give prompt in THIS room, givers get Payaza's checkout popup
        in the room (see `checkout` on opening a gift), and we confirm each payment with Payaza. Other links, and the
        same links in other rooms, still open in a new tab. Use TEST with a test key to try it first. Replaces any
        earlier registration.""")
    @ApiResponse(responseCode = "200", description = "Registered (key shown shortened)")
    @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "403", description = "FORBIDDEN_ROLE: owner and admins only",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "LINK_BLOCKED",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "422", description = "UNSUPPORTED_PAYMENT_LINK: not a Payaza payment link",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public PayazaAccountResponse save(@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
                                      @PathVariable UUID spaceId, @Valid @RequestBody PayazaAccountRequest request) {
        return accounts.save(principal, spaceId, request);
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Remove the room's Payaza account", description = "Giving goes back to opening links in a new tab.")
    @ApiResponse(responseCode = "204", description = "Removed (or there was none)")
    public void remove(@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal, @PathVariable UUID spaceId) {
        accounts.remove(principal, spaceId);
    }
}
