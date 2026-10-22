package com.mywatchmate.identity;

import com.mywatchmate.auth.AuthPrincipal;
import com.mywatchmate.config.OpenApiConfig;
import com.mywatchmate.auth.dto.TokenResponse;
import com.mywatchmate.identity.dto.ChangePasswordRequest;
import com.mywatchmate.identity.dto.MeResponse;
import com.mywatchmate.identity.dto.UpdateProfileRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/me")
@RequiredArgsConstructor
@SecurityRequirement(name = OpenApiConfig.BEARER)
@Tag(name = "Profile", description = "The signed-in user's own account. Registered users only (guests get 403).")
@ApiResponse(responseCode = "401", description = "UNAUTHENTICATED: missing, expired or invalid access token",
    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
@ApiResponse(responseCode = "403", description = "FORBIDDEN: guest tokens can't use these endpoints",
    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
public class ProfileController {

    private final ProfileService profiles;

    @GetMapping
    @Operation(summary = "Get my profile")
    @ApiResponse(responseCode = "200", description = "Profile")
    public MeResponse me(@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal) {
        return profiles.me(principal.userId());
    }

    @PatchMapping
    @Operation(summary = "Update my name or timezone", description = "Send only the fields you want to change.")
    @ApiResponse(responseCode = "200", description = "Updated profile")
    @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED or INVALID_TIMEZONE",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public MeResponse update(@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
                             @Valid @RequestBody UpdateProfileRequest request) {
        return profiles.update(principal.userId(), request);
    }

    @PutMapping("/password")
    @Operation(summary = "Change my password",
        description = "Signs out all devices and returns a fresh token pair for this one; replace stored tokens.")
    @ApiResponse(responseCode = "200", description = "Password changed; new tokens returned")
    @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED, CURRENT_PASSWORD_INCORRECT or PASSWORD_TOO_LONG",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public TokenResponse changePassword(@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
                                        @Valid @RequestBody ChangePasswordRequest request,
                                        @Parameter(hidden = true) @RequestHeader(value = HttpHeaders.USER_AGENT, required = false) String userAgent) {
        return profiles.changePassword(principal.userId(), request, userAgent);
    }
}
