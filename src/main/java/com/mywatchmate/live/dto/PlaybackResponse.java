package com.mywatchmate.live.dto;

import com.mywatchmate.live.PlaybackState;
import com.mywatchmate.watch.ContentItem;
import com.mywatchmate.watch.Participant;
import com.mywatchmate.watch.dto.PersonRef;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

@Schema(description = """
    Where the room's video is. If PLAYING, where you should be right now is
    positionMs + (serverNow - serverTimeMs), using the clock offset from GET /time; otherwise positionMs.""")
public record PlaybackResponse(
    @Schema(description = "The video this is about (the room's now playing); null when nothing is playing", nullable = true)
    UUID contentId,
    @Schema(description = "STOPPED: nothing to play. A finished video is reported PAUSED at its end.", example = "PLAYING")
    PlaybackState.Status status,
    @Schema(description = "Position at serverTimeMs, in milliseconds", example = "754000") long positionMs,
    @Schema(description = "The server time positionMs is true at (epoch milliseconds)", example = "1793577600000")
    long serverTimeMs,
    @Schema(description = "Video length in milliseconds; null when unknown or live", nullable = true) Long durationMs,
    @Schema(description = "A live stream: join at the live point; SEEK is refused") boolean live,
    @Schema(description = "Goes up on every change. Ignore a state with a lower version than the one you have.", example = "42")
    long version,
    @Schema(description = "Who pressed it; null when it changed by itself (new video, session start)", nullable = true)
    PersonRef updatedBy
) {
    public static PlaybackResponse of(PlaybackState state, Instant now) {
        ContentItem content = state.getContent();
        Participant by = state.getUpdatedBy();
        return new PlaybackResponse(content == null ? null : content.getId(), state.statusAt(now), state.positionAt(now),
            now.toEpochMilli(), state.lengthMs(), content != null && content.isLive(), state.getVersion(),
            by == null ? null : new PersonRef(by.getId(), by.displayName()));
    }
}
