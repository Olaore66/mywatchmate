package com.mywatchmate.connect.dto;

import com.mywatchmate.watch.dto.ParticipantResponse;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

@Schema(description = "Sent as MEMBER_MUTED: this person can't send chat messages for now")
public record MemberMuted(
    ParticipantResponse participant,
    @Schema(description = "Null: until a host unmutes them", nullable = true) Instant mutedUntil
) {
}
