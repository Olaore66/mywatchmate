package com.mywatchmate.notify.dto;

import com.mywatchmate.watch.Participant;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "My reminders for one room")
public record ReminderSettingsResponse(
    boolean enabled,
    @Schema(example = "10", nullable = true) Integer minutesBefore,
    @Schema(description = "My account-wide email switch (PATCH /me emailReminders). If false, nothing is sent anywhere.")
    boolean emailRemindersOn
) {
    public static ReminderSettingsResponse of(Participant me) {
        Integer minutes = me.getReminderMinutesBefore();
        return new ReminderSettingsResponse(minutes != null, minutes, me.getUser().isEmailReminders());
    }
}
