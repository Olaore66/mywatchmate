package com.mywatchmate.notify;

import com.mywatchmate.auth.AuthPrincipal;
import com.mywatchmate.config.OpenApiConfig;
import com.mywatchmate.notify.dto.RegisterDeviceRequest;
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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/me/devices")
@RequiredArgsConstructor
@SecurityRequirement(name = OpenApiConfig.BEARER)
@Tag(name = "Devices", description = """
    Register a phone or browser for push notifications. Tokens are stored now; push reminders will be
    switched on server-side later, with no change needed in the app.""")
@ApiResponse(responseCode = "401", description = "UNAUTHENTICATED",
    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
@ApiResponse(responseCode = "403", description = "FORBIDDEN: guests can't register devices",
    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
public class DeviceController {

    private final DeviceService devices;

    @PostMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Register this device", description = "Call after sign-in and whenever the push token changes. Repeats are harmless.")
    @ApiResponse(responseCode = "204", description = "Registered")
    @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public void register(@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
                         @Valid @RequestBody RegisterDeviceRequest request) {
        devices.register(principal.userId(), request);
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Unregister this device", description = "Call on sign-out. Always 204.")
    @ApiResponse(responseCode = "204", description = "Unregistered")
    public void unregister(@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
                           @RequestParam String token) {
        devices.unregister(principal.userId(), token);
    }
}
