package com.mywatchmate.give;

import com.mywatchmate.common.ApiException;
import com.mywatchmate.connect.call.CallService;
import com.mywatchmate.connect.call.LiveKitProperties;
import com.mywatchmate.connect.call.LiveKitRoomClient;
import com.mywatchmate.connect.call.LiveKitTokens;
import com.mywatchmate.connect.call.dto.CallParticipant;
import com.mywatchmate.give.dto.LiveSessionFeed;
import com.mywatchmate.give.dto.WatchTokenResponse;
import com.mywatchmate.live.PlaybackService;
import com.mywatchmate.watch.ContentItem;
import com.mywatchmate.watch.WatchSession;
import com.mywatchmate.watch.WatchSessionRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClientException;

/**
 * What the AI cue worker needs to watch live rooms: which sessions are live and what's playing, and a hidden,
 * view-only way into a room's call to read a shared screen. Video we may read ourselves (direct streams) comes with
 * its address; YouTube and Vimeo never do, since downloading them isn't allowed.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkerFeedService {

    static final String WORKER_IDENTITY = "cue-worker";
    static final Set<ContentItem.Source> READABLE = Set.of(ContentItem.Source.URL, ContentItem.Source.LIVESTREAM);

    private final WatchSessionRepository sessions;
    private final LiveKitProperties livekitProps;
    private final LiveKitRoomClient livekit;
    private final LiveKitTokens tokens;
    private final PlaybackService playback;
    private final Clock clock;

    @Transactional(readOnly = true)
    public List<LiveSessionFeed> liveSessions() {
        return sessions.findLiveWithContent().stream().map(this::feedOf).toList();
    }

    private LiveSessionFeed feedOf(WatchSession session) {
        ContentItem playing = session.getSpace().getCurrentContent();
        ContentItem.Source source = playing == null ? null : playing.getSource();
        String streamUrl = source != null && READABLE.contains(source) ? playing.getExternalUrl() : null;
        return new LiveSessionFeed(session.getId(), session.getSpace().getId(), session.getStartedAt(), source, streamUrl,
            screenShareOn(session.getSpace().getId()), playback.current(session.getSpace()));
    }

    /** Only asked of LiveKit when calls are on; a LiveKit hiccup just means "not now" (asked again next round). */
    private boolean screenShareOn(UUID spaceId) {
        if (!livekitProps.configured()) {
            return false;
        }
        try {
            return livekit.listParticipants(CallService.roomName(spaceId)).stream().anyMatch(CallParticipant::screenSharing);
        } catch (RestClientException e) {
            log.warn("Couldn't ask LiveKit about room {}: {}", spaceId, e.getMessage());
            return false;
        }
    }

    @Transactional(readOnly = true)
    public WatchTokenResponse watchToken(UUID sessionId) {
        if (!livekitProps.configured()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "CALLS_DISABLED", "Calls aren't set up, so there's nothing to watch.");
        }
        WatchSession session = sessions.findById(sessionId)
            .filter(WatchSession::isLive)
            .orElseThrow(() -> ApiException.conflict("SESSION_NOT_LIVE", "That session isn't live."));
        Instant now = clock.instant();
        String room = CallService.roomName(session.getSpace().getId());
        return new WatchTokenResponse(livekitProps.url(), tokens.watchToken(room, WORKER_IDENTITY, now), room, WORKER_IDENTITY,
            now.plus(livekitProps.tokenTtl()));
    }
}
