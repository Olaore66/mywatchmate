package com.mywatchmate.give.dto;

import com.mywatchmate.live.dto.PlaybackResponse;
import com.mywatchmate.watch.ContentItem;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

@Schema(description = "A live session for the AI cue worker to watch")
public record LiveSessionFeed(
    UUID sessionId,
    UUID spaceId,
    Instant startedAt,
    @Schema(description = "What's playing: YOUTUBE, VIMEO, URL or LIVESTREAM; null when nothing is", nullable = true)
    ContentItem.Source source,
    @Schema(description = "A stream the worker may read itself (URL and LIVESTREAM only); null for YouTube and Vimeo, "
        + "which must not be downloaded", nullable = true) String streamUrl,
    @Schema(description = "Someone is sharing their screen in the room's call: the worker can watch it with a watch token")
    boolean screenShare,
    @Schema(description = "Where the room's video is (as for the apps): a recording is read from here, in real time, and "
        + "read again from the new spot whenever `version` changes; nothing is read while PAUSED or STOPPED")
    PlaybackResponse playback
) {
}
