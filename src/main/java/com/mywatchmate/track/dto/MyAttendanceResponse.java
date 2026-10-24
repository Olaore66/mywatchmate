package com.mywatchmate.track.dto;

import com.mywatchmate.track.Attendance;
import com.mywatchmate.watch.WatchSession;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

@Schema(description = "One of my sessions")
public record MyAttendanceResponse(
    UUID sessionId,
    @Schema(example = "Morning Devotion", nullable = true) String sessionTitle,
    UUID spaceId,
    @Schema(example = "6am Prayer Room") String spaceTitle,
    Instant startedAt,
    @Schema(nullable = true) Instant endedAt,
    @Schema(example = "1260") int watchedSeconds,
    @Schema(description = "Counted towards my streak; null while the session is still live", nullable = true) Boolean counted
) {
    public static MyAttendanceResponse from(Attendance a) {
        WatchSession s = a.getSession();
        boolean decided = s.getStatus() == WatchSession.Status.ENDED;
        return new MyAttendanceResponse(s.getId(), s.getTitle(), s.getSpace().getId(), s.getSpace().getTitle(),
            s.getStartedAt(), s.getEndedAt(), a.getWatchedSeconds(), decided ? a.isCounted() : null);
    }
}
