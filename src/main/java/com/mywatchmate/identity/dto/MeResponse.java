package com.mywatchmate.identity.dto;

import com.mywatchmate.identity.User;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

@Schema(description = "The signed-in user's profile")
public record MeResponse(
    @Schema(example = "3f1c2a4e-8b7d-4c1a-9e2f-5a6b7c8d9e0f") UUID id,
    @Schema(example = "ada@example.com") String email,
    @Schema(example = "Ada Okafor") String displayName,
    @Schema(description = "IANA timezone used for reminders and streaks", example = "Europe/London") String timezone,
    @Schema(description = "Reminder emails on for all rooms (each room also has its own setting)") boolean emailReminders,
    @Schema(example = "2026-10-22T09:15:30Z") Instant createdAt
) {
    public static MeResponse from(User user) {
        return new MeResponse(user.getId(), user.getEmail(), user.getDisplayName(), user.getTimezone(),
            user.isEmailReminders(), user.getCreatedAt());
    }
}
