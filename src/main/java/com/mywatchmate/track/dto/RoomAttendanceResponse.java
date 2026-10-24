package com.mywatchmate.track.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Schema(description = "A room's attendance over a period (owner and admins only). Ended sessions only.")
public record RoomAttendanceResponse(
    Instant from,
    Instant to,
    @Schema(description = "Oldest first") List<SessionSummary> sessions,
    @Schema(description = "Current members, in the order they joined") List<MemberSummary> members
) {
    @Schema(name = "RoomSessionSummary")
    public record SessionSummary(
        UUID sessionId,
        @Schema(nullable = true) String title,
        Instant startedAt,
        @Schema(example = "8") int attendedCount,
        @Schema(example = "10") int expectedCount
    ) {
    }

    @Schema(name = "RoomMemberSummary")
    public record MemberSummary(
        UUID participantId,
        String displayName,
        boolean guest,
        @Schema(description = "Sessions they counted for", example = "9") int attended,
        @Schema(description = "Sessions held while they were a member", example = "12") int expected,
        @Schema(nullable = true) Instant lastAttendedAt,
        @Schema(description = "Missed the last 3 sessions they were expected at; maybe check in on them")
        boolean driftingAway
    ) {
    }
}
