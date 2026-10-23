package com.mywatchmate.notify;

import com.mywatchmate.auth.AuthPrincipal;
import com.mywatchmate.config.OpenApiConfig;
import com.mywatchmate.notify.dto.ReminderSettingsRequest;
import com.mywatchmate.notify.dto.ReminderSettingsResponse;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/spaces/{spaceId}/reminders")
@RequiredArgsConstructor
@SecurityRequirement(name = OpenApiConfig.BEARER)
@Tag(name = "Reminders", description = """
    Email reminders before sessions, for registered members (guests get none). On by default, 10 minutes before,
    set per room here. `PATCH /me {"emailReminders": false}` switches all reminder emails off.""")
@ApiResponse(responseCode = "401", description = "UNAUTHENTICATED",
    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
@ApiResponse(responseCode = "403", description = "FORBIDDEN: guests have no reminders",
    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
@ApiResponse(responseCode = "404", description = "SPACE_NOT_FOUND",
    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
public class ReminderSettingsController {

    private final ReminderService reminders;

    @GetMapping
    @Operation(summary = "My reminder setting for this room")
    @ApiResponse(responseCode = "200", description = "Setting")
    public ReminderSettingsResponse get(@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
                                        @PathVariable UUID spaceId) {
        return reminders.settings(principal, spaceId);
    }

    @PutMapping
    @Operation(summary = "Change my reminder setting for this room", description = "Applies to upcoming sessions straight away.")
    @ApiResponse(responseCode = "200", description = "Updated setting")
    @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED (minutesBefore 0–1440)",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public ReminderSettingsResponse update(@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
                                           @PathVariable UUID spaceId, @Valid @RequestBody ReminderSettingsRequest request) {
        return reminders.updateSettings(principal, spaceId, request);
    }
}
