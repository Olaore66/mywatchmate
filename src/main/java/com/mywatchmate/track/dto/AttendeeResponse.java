package com.mywatchmate.track.dto;

import com.mywatchmate.track.Attendance;
import com.mywatchmate.watch.Participant;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

@Schema(description = "Someone who was in a session")
public record AttendeeResponse(
    UUID participantId,
    @Schema(example = "Bisi") String displayName,
    boolean guest,
    @Schema(description = "First seen in the live session") Instant joinedAt,
    @Schema(description = "Last seen in it") Instant lastSeenAt,
    @Schema(example = "1260") int watchedSeconds,
    @Schema(description = "Watched enough to count; null while the session is still live", nullable = true) Boolean counted
) {
    public static AttendeeResponse from(Attendance a, boolean decided) {
        Participant p = a.getParticipant();
        return new AttendeeResponse(p.getId(), p.displayName(), p.isGuest(), a.getJoinedAt(), a.getLastSeenAt(),
            a.getWatchedSeconds(), decided ? a.isCounted() : null);
    }
}
