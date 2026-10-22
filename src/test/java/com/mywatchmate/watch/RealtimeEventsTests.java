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
    private StompSession session;

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
        if (session != null && session.isConnected()) {
            try {
                session.disconnect();
            } catch (MessageDeliveryException alreadyClosed) {
                // The server may have just closed it (e.g. after an ERROR frame)
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
        session = connect(ada, new LinkedBlockingQueue<>());
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
    }

    @Test
    void outsidersAreCutOffWhenTheyTryToListenToARoom() throws Exception {
        String ada = register("Ada");
        String spaceId = post("/api/v1/spaces", ada, "{\"title\":\"Private\",\"audienceType\":\"FRIENDS\"}").read("$.id");
        String kemi = register("Kemi");

        BlockingQueue<String> errors = new LinkedBlockingQueue<>();
        session = connect(kemi, errors);
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
        return stomp.connectAsync(wsUrl(), new WebSocketHttpHeaders(), headers, handler(errors)).get(5, TimeUnit.SECONDS);
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

    /** Events are JSON objects; collect them as maps for JsonPath. */
    private static StompFrameHandler collectInto(BlockingQueue<Object> queue) {
        return new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return Map.class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                queue.offer(payload);
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
