package com.mywatchmate.auth;

import com.mywatchmate.auth.dto.GuestJoinRequest;
import com.mywatchmate.auth.dto.GuestRefreshRequest;
import com.mywatchmate.auth.dto.GuestTokenResponse;
import com.mywatchmate.auth.dto.LoginRequest;
import com.mywatchmate.auth.dto.RefreshRequest;
import com.mywatchmate.auth.dto.RegisterRequest;
import com.mywatchmate.auth.dto.TokenResponse;
import com.mywatchmate.config.OpenApiConfig;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
@Tag(name = "Auth", description = """
    Sign-up, sign-in and session tokens for users and guests.

    **Users:** register or login → keep `accessToken` (15 min) in memory and `refreshToken` (30 days) in secure storage.
    When a call returns 401, call `/auth/refresh` once with the refresh token; you get a NEW pair (the old refresh
    token stops working). If refresh fails, send the user to sign-in.

    **Guests:** `/auth/guest` with an invite token → keep `deviceToken` on the device; get new access tokens
    from `/auth/guest/refresh`.""")
public class AuthController {

    private final AuthService auth;

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create an account", description = "Creates the user and signs them in.")
    @ApiResponse(responseCode = "201", description = "Account created; tokens returned")
    @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED, INVALID_TIMEZONE or PASSWORD_TOO_LONG",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "EMAIL_TAKEN",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public TokenResponse register(@Valid @RequestBody RegisterRequest request,
                                  @Parameter(hidden = true) @RequestHeader(value = HttpHeaders.USER_AGENT, required = false) String userAgent) {
        return auth.register(request, userAgent);
    }

    @PostMapping("/login")
    @Operation(summary = "Sign in with email and password")
    @ApiResponse(responseCode = "200", description = "Signed in; tokens returned")
    @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "401", description = "INVALID_CREDENTIALS (same response for unknown email and wrong password)",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public TokenResponse login(@Valid @RequestBody LoginRequest request,
                               @Parameter(hidden = true) @RequestHeader(value = HttpHeaders.USER_AGENT, required = false) String userAgent) {
        return auth.login(request, userAgent);
    }

    @PostMapping("/refresh")
    @Operation(summary = "Exchange a refresh token for a new token pair",
        description = "Refresh tokens are single use. Always replace the stored refresh token with the new one. "
            + "Re-using an old refresh token signs that login out everywhere (REFRESH_TOKEN_REUSED).")
    @ApiResponse(responseCode = "200", description = "New access + refresh token")
    @ApiResponse(responseCode = "401", description = "INVALID_REFRESH_TOKEN, REFRESH_TOKEN_EXPIRED or REFRESH_TOKEN_REUSED → sign in again",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public TokenResponse refresh(@Valid @RequestBody RefreshRequest request,
                                 @Parameter(hidden = true) @RequestHeader(value = HttpHeaders.USER_AGENT, required = false) String userAgent) {
        return auth.refresh(request.refreshToken(), userAgent);
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @SecurityRequirement(name = OpenApiConfig.BEARER)
    @Operation(summary = "Sign out this device", description = "Revokes the given refresh token's login. "
        + "Always returns 204; then discard both tokens on the device.")
    @ApiResponse(responseCode = "204", description = "Signed out")
    @ApiResponse(responseCode = "401", description = "UNAUTHENTICATED: missing or invalid access token",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "403", description = "FORBIDDEN: guests use no refresh tokens",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public void logout(@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
                       @Valid @RequestBody RefreshRequest request) {
        auth.logout(principal, request.refreshToken());
    }

    @PostMapping("/guest")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Join a space as a guest (no account)",
        description = "Creates a guest participant in the space behind the invite link.")
    @ApiResponse(responseCode = "201", description = "Guest created; tokens returned")
    @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "403", description = "GUESTS_NOT_ALLOWED: the space requires an account",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "INVITE_NOT_FOUND",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public GuestTokenResponse joinAsGuest(@Valid @RequestBody GuestJoinRequest request) {
        return auth.joinAsGuest(request);
    }

    @PostMapping("/guest/refresh")
    @Operation(summary = "Get a new guest access token", description = "The device token is not rotated; "
        + "`deviceToken` is null in this response, keep using the one you have.")
    @ApiResponse(responseCode = "200", description = "New guest access token")
    @ApiResponse(responseCode = "401", description = "INVALID_DEVICE_TOKEN: guest left, upgraded, or the space was archived",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public GuestTokenResponse refreshGuest(@Valid @RequestBody GuestRefreshRequest request) {
        return auth.refreshGuest(request.deviceToken());
    }

    @PostMapping("/upgrade")
    @ResponseStatus(HttpStatus.CREATED)
    @SecurityRequirement(name = OpenApiConfig.BEARER)
    @Operation(summary = "Turn the current guest into a registered user",
        description = "Call with the GUEST access token. The guest's history in the space moves to the new account; "
            + "the guest device token stops working. Store the returned user tokens instead.")
    @ApiResponse(responseCode = "201", description = "Account created; user tokens returned")
    @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED, INVALID_TIMEZONE or PASSWORD_TOO_LONG",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "403", description = "FORBIDDEN: only guests can upgrade",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "EMAIL_TAKEN or ALREADY_UPGRADED",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public TokenResponse upgrade(@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
                                 @Valid @RequestBody RegisterRequest request,
                                 @Parameter(hidden = true) @RequestHeader(value = HttpHeaders.USER_AGENT, required = false) String userAgent) {
        return auth.upgradeGuest(principal, request, userAgent);
    }
}
