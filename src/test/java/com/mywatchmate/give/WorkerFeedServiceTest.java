package com.mywatchmate.give;

import static com.mywatchmate.testsupport.Fixtures.MONDAY_MIDNIGHT;
import static com.mywatchmate.testsupport.Fixtures.assertApiError;
import static com.mywatchmate.testsupport.Fixtures.clockAt;
import static com.mywatchmate.testsupport.Fixtures.session;
import static com.mywatchmate.testsupport.Fixtures.space;
import static com.mywatchmate.testsupport.Fixtures.user;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.mywatchmate.connect.call.CallService;
import com.mywatchmate.connect.call.LiveKitProperties;
import com.mywatchmate.connect.call.LiveKitRoomClient;
import com.mywatchmate.connect.call.LiveKitTokens;
import com.mywatchmate.connect.call.dto.CallParticipant;
import com.mywatchmate.give.dto.LiveSessionFeed;
import com.mywatchmate.give.dto.WatchTokenResponse;
import com.mywatchmate.watch.ContentItem;
import com.mywatchmate.watch.Space;
import com.mywatchmate.watch.WatchSession;
import com.mywatchmate.watch.WatchSessionRepository;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.ResourceAccessException;

@ExtendWith(MockitoExtension.class)
class WorkerFeedServiceTest {

    @Mock WatchSessionRepository sessions;
    @Mock LiveKitRoomClient livekit;
    @Mock LiveKitTokens tokens;

    private final LiveKitProperties callsOn = new LiveKitProperties("wss://calls.example", "key", "a-secret-of-at-least-32-characters!", Duration.ofMinutes(10));
    private final LiveKitProperties callsOff = new LiveKitProperties("", "", "", Duration.ofMinutes(10));

    private WorkerFeedService service(LiveKitProperties props) {
        return new WorkerFeedService(sessions, props, livekit, tokens, clockAt(MONDAY_MIDNIGHT));
    }

    private WatchSession liveRoomPlaying(ContentItem.Source source, String url) {
        Space space = space(user("Ada"));
        if (source != null) {
            ContentItem item = new ContentItem(space, source, url);
            item.setId(UUID.randomUUID());
            space.playNow(item);
        }
        WatchSession live = session(space, MONDAY_MIDNIGHT);
        live.start(MONDAY_MIDNIGHT);
        return live;
    }

    private static CallParticipant sharing(boolean screen) {
        return new CallParticipant(UUID.randomUUID(), "Host", MONDAY_MIDNIGHT, true, true, screen);
    }

    @Test
    void directStreamsComeWithTheirAddressAndYouTubeNeverDoes() {
        WatchSession stream = liveRoomPlaying(ContentItem.Source.LIVESTREAM, "https://cdn.church.org/live.m3u8");
        WatchSession youtube = liveRoomPlaying(ContentItem.Source.YOUTUBE, "https://www.youtube.com/watch?v=abc");
        WatchSession nothing = liveRoomPlaying(null, null);
        when(sessions.findLiveWithContent()).thenReturn(List.of(stream, youtube, nothing));
        when(livekit.listParticipants(CallService.roomName(stream.getSpace().getId()))).thenReturn(List.of());
        when(livekit.listParticipants(CallService.roomName(youtube.getSpace().getId()))).thenReturn(List.of(sharing(false), sharing(true)));
        when(livekit.listParticipants(CallService.roomName(nothing.getSpace().getId()))).thenReturn(List.of(sharing(false)));

        List<LiveSessionFeed> feed = service(callsOn).liveSessions();

        assertThat(feed).extracting(LiveSessionFeed::sessionId).containsExactly(stream.getId(), youtube.getId(), nothing.getId());
        assertThat(feed.get(0).streamUrl()).isEqualTo("https://cdn.church.org/live.m3u8");
        assertThat(feed.get(0).source()).isEqualTo(ContentItem.Source.LIVESTREAM);
        assertThat(feed.get(0).screenShare()).isFalse();
        assertThat(feed.get(1).streamUrl()).as("never downloaded").isNull();
        assertThat(feed.get(1).screenShare()).isTrue();
        assertThat(feed.get(2).source()).isNull();
        assertThat(feed.get(2).screenShare()).isFalse();
    }

    @Test
    void withCallsOffOrLiveKitDownThereIsSimplyNoScreenShare() {
        WatchSession url = liveRoomPlaying(ContentItem.Source.URL, "https://cdn.church.org/sunday.mp4");
        when(sessions.findLiveWithContent()).thenReturn(List.of(url));

        assertThat(service(callsOff).liveSessions().getFirst().screenShare()).isFalse();
        verifyNoInteractions(livekit);

        when(livekit.listParticipants(CallService.roomName(url.getSpace().getId()))).thenThrow(new ResourceAccessException("down"));
        LiveSessionFeed feed = service(callsOn).liveSessions().getFirst();
        assertThat(feed.screenShare()).isFalse();
        assertThat(feed.streamUrl()).isEqualTo("https://cdn.church.org/sunday.mp4");
    }

    @Test
    void aWatchTokenIsForALiveSessionsRoomWhenCallsAreOn() {
        WatchSession live = liveRoomPlaying(ContentItem.Source.YOUTUBE, "https://youtu.be/abc");
        when(sessions.findById(live.getId())).thenReturn(Optional.of(live));
        String room = CallService.roomName(live.getSpace().getId());
        when(tokens.watchToken(room, "cue-worker", MONDAY_MIDNIGHT)).thenReturn("signed");

        WatchTokenResponse token = service(callsOn).watchToken(live.getId());

        assertThat(token.token()).isEqualTo("signed");
        assertThat(token.roomName()).isEqualTo(room);
        assertThat(token.identity()).isEqualTo("cue-worker");
        assertThat(token.url()).isEqualTo("wss://calls.example");
        assertThat(token.expiresAt()).isEqualTo(MONDAY_MIDNIGHT.plus(Duration.ofMinutes(10)));
    }

    @Test
    void noTokenForAnEndedSessionOrWhenCallsAreOff() {
        WatchSession ended = liveRoomPlaying(null, null);
        ended.end(MONDAY_MIDNIGHT);
        when(sessions.findById(ended.getId())).thenReturn(Optional.of(ended));
        UUID unknown = UUID.randomUUID();
        when(sessions.findById(unknown)).thenReturn(Optional.empty());

        assertApiError(() -> service(callsOn).watchToken(ended.getId()), HttpStatus.CONFLICT, "SESSION_NOT_LIVE");
        assertApiError(() -> service(callsOn).watchToken(unknown), HttpStatus.CONFLICT, "SESSION_NOT_LIVE");
        assertApiError(() -> service(callsOff).watchToken(ended.getId()), HttpStatus.SERVICE_UNAVAILABLE, "CALLS_DISABLED");
        verifyNoInteractions(tokens);
    }
}
