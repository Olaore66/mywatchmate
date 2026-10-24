package com.mywatchmate.watch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import com.mywatchmate.watch.content.ResolvedLink;
import com.mywatchmate.watch.content.VideoMetadata;
import com.mywatchmate.watch.content.VideoMetadataClient;
import java.lang.reflect.Type;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.converter.CompositeMessageConverter;
import org.springframework.messaging.converter.JacksonJsonMessageConverter;
import org.springframework.messaging.converter.StringMessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

/**
 * End to end, the way the frontend works: a real server, REST calls over HTTP, and a STOMP
 * client on /ws receiving the room's events. Not transactional (events only go out after
 * commit), so it leaves a few test rows behind with random emails.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RealtimeEventsTests {

    @LocalServerPort int port;
    @MockitoBean VideoMetadataClient metadata;

    private final HttpClient http = HttpClient.newHttpClient();
    private WebSocketStompClient stomp;
    private final List<StompSession> sessions = new ArrayList<>();

    @BeforeEach
    void setUp() {
        when(metadata.fetch(any(ResolvedLink.class))).thenReturn(new VideoMetadata("Morning Prayer", null, null));
        stomp = new WebSocketStompClient(new StandardWebSocketClient());
        // Events arrive as application/json; ERROR frames as text/plain
        stomp.setMessageConverter(new CompositeMessageConverter(
            List.of(new StringMessageConverter(), new JacksonJsonMessageConverter())));
    }

    @AfterEach
    void tearDown() {
        for (StompSession session : sessions) {
            if (session.isConnected()) {
                try {
                    session.disconnect();
                } catch (MessageDeliveryException alreadyClosed) {
                    // The server may have just closed it (e.g. after an ERROR frame)
                }
            }
        }
        stomp.stop();
    }

    @Test
    void membersHearJoinsAndNowPlayingChangesLive() throws Exception {
        String ada = register("Ada");
        String spaceId = post("/api/v1/spaces", ada, "{\"title\":\"6am Prayer\",\"audienceType\":\"DIASPORA\"}").read("$.id");
        String invite = get("/api/v1/spaces/" + spaceId + "/invite", ada).read("$.inviteToken");

        BlockingQueue<Object> events = new LinkedBlockingQueue<>();
        StompSession session = connect(ada, new LinkedBlockingQueue<>());
        session.subscribe("/topic/spaces/" + spaceId + "/events", collectInto(events));
        Thread.sleep(300); // let the SUBSCRIBE frame reach the broker before triggering events

        post("/api/v1/auth/guest", null, "{\"inviteToken\":\"%s\",\"displayName\":\"Bisi\"}".formatted(invite));
        DocumentContext joined = JsonPath.parse(next(events));
        assertThat(joined.read("$.type", String.class)).isEqualTo("MEMBER_JOINED");
        assertThat(joined.read("$.spaceId", String.class)).isEqualTo(spaceId);
        assertThat(joined.read("$.data.displayName", String.class)).isEqualTo("Bisi");
        assertThat(joined.read("$.data.guest", Boolean.class)).isTrue();
        assertThat(joined.read("$.at", String.class)).matches("\\d{4}-\\d{2}-\\d{2}T.*Z");

        post("/api/v1/spaces/" + spaceId + "/content", ada, "{\"url\":\"https://youtu.be/dQw4w9WgXcQ\"}");
        assertThat(JsonPath.parse(next(events)).read("$.type", String.class)).isEqualTo("CONTENT_ADDED");
        DocumentContext playing = JsonPath.parse(next(events));
        assertThat(playing.read("$.type", String.class)).isEqualTo("NOW_PLAYING_CHANGED");
        assertThat(playing.read("$.data.externalId", String.class)).isEqualTo("dQw4w9WgXcQ");
        assertThat(playing.read("$.data.title", String.class)).isEqualTo("Morning Prayer");
        DocumentContext fromTheStart = JsonPath.parse(next(events));
        assertThat(fromTheStart.read("$.type", String.class)).isEqualTo("PLAYBACK_CHANGED");
        assertThat(fromTheStart.read("$.data.status", String.class)).isEqualTo("PLAYING");
        assertThat(fromTheStart.read("$.data.positionMs", Long.class)).isLessThan(1000L);

        post("/api/v1/spaces/" + spaceId + "/sessions/watch-now", ada, "{\"title\":\"Catch-up\"}");
        DocumentContext started = JsonPath.parse(next(events));
        assertThat(started.read("$.type", String.class)).isEqualTo("SESSION_STARTED");
        assertThat(started.read("$.data.status", String.class)).isEqualTo("LIVE");
        assertThat(started.read("$.data.title", String.class)).isEqualTo("Catch-up");
        assertThat(started.read("$.data.content.externalId", String.class)).isEqualTo("dQw4w9WgXcQ");
        assertThat(started.read("$.data.localTimezone", String.class)).isEqualTo("UTC");
    }

    @Test
    void eventsArriveInTheOrderTheyHappened() throws Exception {
        String ada = register("Ada");
        String spaceId = post("/api/v1/spaces", ada, "{\"title\":\"Busy room\",\"audienceType\":\"FRIENDS\"}").read("$.id");
        BlockingQueue<Object> events = new LinkedBlockingQueue<>();
        StompSession session = connect(ada, new LinkedBlockingQueue<>());
        session.subscribe("/topic/spaces/" + spaceId + "/events", collectInto(events));
        Thread.sleep(300);

        for (int i = 0; i < 10; i++) {
            post("/api/v1/spaces/" + spaceId + "/content", ada, "{\"url\":\"https://youtu.be/dQw4w9WgX%02d\"}".formatted(i));
        }

        long lastVersion = -1;
        for (int i = 0; i < 10; i++) {
            String expectedId = "dQw4w9WgX%02d".formatted(i);
            DocumentContext added = JsonPath.parse(next(events));
            DocumentContext playing = JsonPath.parse(next(events));
            DocumentContext playback = JsonPath.parse(next(events));
            assertThat(added.read("$.type", String.class)).as("event %d", 3 * i).isEqualTo("CONTENT_ADDED");
            assertThat(added.read("$.data.externalId", String.class)).isEqualTo(expectedId);
            assertThat(playing.read("$.type", String.class)).as("event %d", 3 * i + 1).isEqualTo("NOW_PLAYING_CHANGED");
            assertThat(playing.read("$.data.externalId", String.class)).isEqualTo(expectedId);
            // The effect comes after its cause, and versions only go up
            assertThat(playback.read("$.type", String.class)).as("event %d", 3 * i + 2).isEqualTo("PLAYBACK_CHANGED");
            assertThat(playback.read("$.data.contentId", String.class)).isEqualTo(playing.read("$.data.id", String.class));
            assertThat(playback.read("$.data.version", Long.class)).isGreaterThan(lastVersion);
            lastVersion = playback.read("$.data.version", Long.class);
        }
    }

    @Test
    void onePressPlaysPausesAndSeeksForEveryone() throws Exception {
        String ada = register("Ada");
        String spaceId = post("/api/v1/spaces", ada, """
            {"title":"Movie night","audienceType":"FRIENDS","videoUrl":"https://youtu.be/dQw4w9WgXcQ"}""").read("$.id");
        String invite = get("/api/v1/spaces/" + spaceId + "/invite", ada).read("$.inviteToken");
        String tunde = register("Tunde");
        post("/api/v1/invites/" + invite + "/join", tunde, "");

        BlockingQueue<Object> adaHears = new LinkedBlockingQueue<>();
        connect(ada, new LinkedBlockingQueue<>()).subscribe(eventsOf(spaceId), collectInto(adaHears, "PLAYBACK_CHANGED"));
        Thread.sleep(300);

        // Tunde (a member: friends rooms let members control playback) pauses for everyone
        DocumentContext paused = post("/api/v1/spaces/" + spaceId + "/playback", tunde, "{\"action\":\"PAUSE\"}");
        assertThat(paused.read("$.status", String.class)).isEqualTo("PAUSED");
        DocumentContext heard = JsonPath.parse(next(adaHears));
        assertThat(heard.read("$.data.status", String.class)).isEqualTo("PAUSED");
        assertThat(heard.read("$.data.updatedBy.displayName", String.class)).isEqualTo("Tunde");
        assertThat(heard.read("$.data.version", Long.class)).isEqualTo(paused.read("$.version", Long.class));

        post("/api/v1/spaces/" + spaceId + "/playback", ada, "{\"action\":\"SEEK\",\"positionMs\":754000}");
        DocumentContext seeked = JsonPath.parse(next(adaHears));
        assertThat(seeked.read("$.data.positionMs", Long.class)).isEqualTo(754_000L);
        assertThat(seeked.read("$.data.status", String.class)).as("seeking keeps it paused").isEqualTo("PAUSED");

        // Someone opening the room late gets the same moment from the room details
        DocumentContext room = get("/api/v1/spaces/" + spaceId, tunde);
        assertThat(room.read("$.playback.positionMs", Long.class)).isEqualTo(754_000L);
        assertThat(room.read("$.playback.version", Long.class)).isEqualTo(seeked.read("$.data.version", Long.class));
        assertThat(room.read("$.canControlPlayback", Boolean.class)).isTrue();
    }

    @Test
    void theRoomSeesWhoComesAndGoes() throws Exception {
        String ada = register("Ada");
        String spaceId = post("/api/v1/spaces", ada, "{\"title\":\"Prayer\",\"audienceType\":\"DIASPORA\"}").read("$.id");
        String invite = get("/api/v1/spaces/" + spaceId + "/invite", ada).read("$.inviteToken");
        String bisi = post("/api/v1/auth/guest", null, "{\"inviteToken\":\"%s\",\"displayName\":\"Bisi\"}"
            .formatted(invite)).read("$.accessToken");

        BlockingQueue<Object> presence = new LinkedBlockingQueue<>();
        connect(ada, new LinkedBlockingQueue<>()).subscribe(eventsOf(spaceId), collectInto(presence, "PRESENCE_CHANGED"));
        awaitOnline(spaceId, ada, 1);
        presence.clear();

        // Bisi opens the room on two devices: online once
        StompSession phone = connect(bisi, new LinkedBlockingQueue<>());
        phone.subscribe(eventsOf(spaceId), collectInto(new LinkedBlockingQueue<>()));
        DocumentContext arrived = JsonPath.parse(next(presence));
        assertThat(arrived.read("$.data.onlineCount", Integer.class)).isEqualTo(2);
        assertThat(arrived.read("$.data.online[1].displayName", String.class)).isEqualTo("Bisi");
        assertThat(arrived.read("$.data.online[1].guest", Boolean.class)).isTrue();
        StompSession laptop = connect(bisi, new LinkedBlockingQueue<>());
        laptop.subscribe(eventsOf(spaceId), collectInto(new LinkedBlockingQueue<>()));
        awaitOnline(spaceId, ada, 2);
        assertThat(get("/api/v1/spaces/" + spaceId, ada).read("$.onlineCount", Integer.class)).isEqualTo(2);

        // Closing one keeps her online; closing both doesn't
        phone.disconnect();
        Thread.sleep(300);
        assertThat(presence.poll()).as("still online on the laptop").isNull();
        laptop.disconnect();
        DocumentContext left = JsonPath.parse(next(presence));
        assertThat(left.read("$.data.onlineCount", Integer.class)).isEqualTo(1);
        assertThat(left.read("$.data.online[0].displayName", String.class)).isEqualTo("Ada");
    }

    @Test
    void someoneRemovedStopsHearingTheRoomAtOnce() throws Exception {
        String ada = register("Ada");
        String spaceId = post("/api/v1/spaces", ada, "{\"title\":\"Prayer\",\"audienceType\":\"DIASPORA\"}").read("$.id");
        String invite = get("/api/v1/spaces/" + spaceId + "/invite", ada).read("$.inviteToken");
        DocumentContext guest = post("/api/v1/auth/guest", null, "{\"inviteToken\":\"%s\",\"displayName\":\"Bisi\"}"
            .formatted(invite));
        String bisi = guest.read("$.accessToken");

        StompSession bisiSession = connect(bisi, new LinkedBlockingQueue<>());
        bisiSession.subscribe(eventsOf(spaceId), collectInto(new LinkedBlockingQueue<>()));
        awaitOnline(spaceId, ada, 1);

        delete("/api/v1/spaces/" + spaceId + "/participants/" + guest.read("$.participantId"), ada);

        for (int i = 0; i < 50 && bisiSession.isConnected(); i++) {
            Thread.sleep(100);
        }
        assertThat(bisiSession.isConnected()).as("the server closed Bisi's connection").isFalse();
        awaitOnline(spaceId, ada, 0);
    }

    @Test
    void sharedNotesAndTheAttendanceCountReachTheRoomPrivateNotesDont() throws Exception {
        String ada = register("Ada");
        String spaceId = post("/api/v1/spaces", ada, "{\"title\":\"Prayer\",\"audienceType\":\"DIASPORA\"}").read("$.id");
        String invite = get("/api/v1/spaces/" + spaceId + "/invite", ada).read("$.inviteToken");
        String bisi = post("/api/v1/auth/guest", null, "{\"inviteToken\":\"%s\",\"displayName\":\"Bisi\"}"
            .formatted(invite)).read("$.accessToken");

        BlockingQueue<Object> adaHears = new LinkedBlockingQueue<>();
        connect(ada, new LinkedBlockingQueue<>()).subscribe(eventsOf(spaceId),
            collectInto(adaHears, "NOTE_ADDED", "NOTE_UPDATED", "NOTE_DELETED", "ATTENDANCE_FINALISED"));
        Thread.sleep(300);

        post("/api/v1/spaces/" + spaceId + "/notes", bisi, "{\"body\":\"Only for me\"}");
        String noteId = post("/api/v1/spaces/" + spaceId + "/notes", bisi,
            "{\"body\":\"Amen!\",\"visibility\":\"SHARED\",\"positionSeconds\":750}").read("$.id");
        DocumentContext added = JsonPath.parse(next(adaHears));
        assertThat(added.read("$.type", String.class)).as("the private note was never announced").isEqualTo("NOTE_ADDED");
        assertThat(added.read("$.data.body", String.class)).isEqualTo("Amen!");
        assertThat(added.read("$.data.author.displayName", String.class)).isEqualTo("Bisi");
        assertThat(added.read("$.data.positionSeconds", Integer.class)).isEqualTo(750);

        delete("/api/v1/spaces/" + spaceId + "/notes/" + noteId, ada);
        DocumentContext removed = JsonPath.parse(next(adaHears));
        assertThat(removed.read("$.type", String.class)).isEqualTo("NOTE_DELETED");
        assertThat(removed.read("$.data.noteId", String.class)).isEqualTo(noteId);

        String sessionId = post("/api/v1/spaces/" + spaceId + "/sessions/watch-now", ada, "{}").read("$.id");
        post("/api/v1/spaces/" + spaceId + "/sessions/" + sessionId + "/end", ada, "");
        DocumentContext finalised = JsonPath.parse(next(adaHears));
        assertThat(finalised.read("$.type", String.class)).isEqualTo("ATTENDANCE_FINALISED");
        assertThat(finalised.read("$.data.sessionId", String.class)).isEqualTo(sessionId);
        assertThat(finalised.read("$.data.expectedCount", Integer.class)).as("Ada and Bisi").isEqualTo(2);
    }

    @Test
    void outsidersAreCutOffWhenTheyTryToListenToARoom() throws Exception {
        String ada = register("Ada");
        String spaceId = post("/api/v1/spaces", ada, "{\"title\":\"Private\",\"audienceType\":\"FRIENDS\"}").read("$.id");
        String kemi = register("Kemi");

        BlockingQueue<String> errors = new LinkedBlockingQueue<>();
        StompSession session = connect(kemi, errors);
        session.subscribe("/topic/spaces/" + spaceId + "/events", collectInto(new LinkedBlockingQueue<>()));

        assertThat(errors.poll(5, TimeUnit.SECONDS)).isEqualTo("Not allowed to subscribe to /topic/spaces/" + spaceId + "/events");
    }

    @Test
    void badTokenErrorSaysWhy() throws Exception {
        BlockingQueue<String> errors = new LinkedBlockingQueue<>();
        StompHeaders headers = new StompHeaders();
        headers.add("Authorization", "Bearer not-a-real-token");
        stomp.connectAsync(wsUrl(), new WebSocketHttpHeaders(), headers, handler(errors));

        assertThat(errors.poll(5, TimeUnit.SECONDS)).isEqualTo("Invalid or expired token");
    }

    @Test
    void connectingWithoutATokenIsRefused() {
        BlockingQueue<String> errors = new LinkedBlockingQueue<>();
        StompHeaders noAuth = new StompHeaders();
        assertThat(stomp.connectAsync(wsUrl(), new WebSocketHttpHeaders(), noAuth, handler(errors)))
            .failsWithin(5, TimeUnit.SECONDS);
    }

    // ---- helpers -------------------------------------------------------------

    private StompSession connect(String token, BlockingQueue<String> errors) throws Exception {
        StompHeaders headers = new StompHeaders();
        headers.add("Authorization", "Bearer " + token);
        StompSession session = stomp.connectAsync(wsUrl(), new WebSocketHttpHeaders(), headers, handler(errors))
            .get(5, TimeUnit.SECONDS);
        sessions.add(session);
        return session;
    }

    private static String eventsOf(String spaceId) {
        return "/topic/spaces/" + spaceId + "/events";
    }

    /** Presence follows subscriptions as they arrive; wait until it shows this many people. */
    private void awaitOnline(String spaceId, String token, int expected) throws Exception {
        int online = -1;
        for (int i = 0; i < 50; i++) {
            online = get("/api/v1/spaces/" + spaceId + "/presence", token).read("$.onlineCount", Integer.class);
            if (online == expected) {
                return;
            }
            Thread.sleep(100);
        }
        assertThat(online).as("people online").isEqualTo(expected);
    }

    private static StompSessionHandlerAdapter handler(BlockingQueue<String> errors) {
        return new StompSessionHandlerAdapter() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return String.class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                // Only ERROR frames reach the session handler
                errors.offer(String.valueOf(headers.getFirst("message")));
            }
        };
    }

    /**
     * Events are JSON objects; collect them as maps for JsonPath. Only the given types or, when none
     * are given, everything but PRESENCE_CHANGED (whose timing depends on when each client subscribed).
     */
    private static StompFrameHandler collectInto(BlockingQueue<Object> queue, String... onlyTypes) {
        return new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return Map.class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                Object type = ((Map<?, ?>) payload).get("type");
                boolean wanted = onlyTypes.length == 0
                    ? !"PRESENCE_CHANGED".equals(type)
                    : Arrays.asList(onlyTypes).contains(type);
                if (wanted) {
                    queue.offer(payload);
                }
            }
        };
    }

    private static Object next(BlockingQueue<Object> events) throws InterruptedException {
        Object event = events.poll(5, TimeUnit.SECONDS);
        assertThat(event).as("an event within 5 seconds").isNotNull();
        return event;
    }

    private String wsUrl() {
        return "ws://localhost:" + port + "/ws";
    }

    private String register(String name) throws Exception {
        return post("/api/v1/auth/register", null, """
            {"email":"e2e-%s-%s@example.com","password":"correct-horse-battery","displayName":"%s"}"""
            .formatted(name.toLowerCase(), UUID.randomUUID(), name)).read("$.accessToken");
    }

    private DocumentContext post(String path, String token, String body) throws Exception {
        return send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)), token);
    }

    private void delete(String path, String token) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .header("Authorization", "Bearer " + token).DELETE().build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(204);
    }

    private DocumentContext get(String path, String token) throws Exception {
        return send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET(), token);
    }

    private DocumentContext send(HttpRequest.Builder request, String token) throws Exception {
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isBetween(200, 299);
        return JsonPath.parse(response.body());
    }
}
