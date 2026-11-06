package com.mywatchmate.auth;

import com.mywatchmate.auth.dto.ForgotPasswordRequest;
import com.mywatchmate.auth.dto.ResetPasswordRequest;
import com.mywatchmate.auth.dto.VerifyEmailRequest;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
@Tag(name = "Account recovery", description = """
    Forgotten passwords and email confirmation, through one-time links we email.

    **Screens the frontend needs:**
    - `<FRONTEND_URL>/reset-password?token=…`: ask for a new password, then call `/auth/password/reset`.
    - `<FRONTEND_URL>/verify-email?token=…`: call `/auth/email/verify` on load, then show the result.

    A confirmation email goes out on sign-up and on guest upgrade. Unconfirmed accounts still work:
    use `emailVerified` from `/me` to show a "please confirm your email" banner with a resend button.""")
public class AccountController {

    private final AccountService accounts;

    @PostMapping("/password/forgot")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Email me a password reset link", description = """
        Always 202, whether or not the address has an account, so this can't be used to find out who's registered.
        Show "If that address has an account, we've emailed a link". The link works for 1 hour, once; asking again
        retires the earlier link. At most 3 emails an hour per account.""")
    @ApiResponse(responseCode = "202", description = "Accepted")
    @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public void forgot(@Valid @RequestBody ForgotPasswordRequest request) {
        accounts.forgotPassword(request.email());
    }

    @PostMapping("/password/reset")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Set a new password with the emailed link", description = """
        Signs out every device and emails a "your password was changed" notice. Send the user to sign-in afterwards.
        Also confirms their email, since they opened a link sent to it.""")
    @ApiResponse(responseCode = "204", description = "Password changed")
    @ApiResponse(responseCode = "400", description = "INVALID_OR_EXPIRED_LINK, VALIDATION_FAILED or PASSWORD_TOO_LONG",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public void reset(@Valid @RequestBody ResetPasswordRequest request) {
        accounts.resetPassword(request.token(), request.newPassword());
    }

    @PostMapping("/email/verify")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Confirm an email address with the emailed link", description = "No sign-in needed. "
        + "The link works for 48 hours. Calling it again after success still returns 204.")
    @ApiResponse(responseCode = "204", description = "Confirmed")
    @ApiResponse(responseCode = "400", description = "INVALID_OR_EXPIRED_LINK",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public void verify(@Valid @RequestBody VerifyEmailRequest request) {
        accounts.verifyEmail(request.token());
    }

    @PostMapping("/email/resend")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @SecurityRequirement(name = OpenApiConfig.BEARER)
    @Operation(summary = "Send me a new confirmation email", description = "Earlier links stop working. "
        + "At most 3 emails an hour.")
    @ApiResponse(responseCode = "202", description = "Sending")
    @ApiResponse(responseCode = "401", description = "UNAUTHENTICATED",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "403", description = "FORBIDDEN (guests have no email)",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "EMAIL_ALREADY_VERIFIED",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "429", description = "TOO_MANY_EMAILS",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public void resend(@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal) {
        accounts.resendVerification(principal.userId());
    }
}
