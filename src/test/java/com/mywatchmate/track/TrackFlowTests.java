package com.mywatchmate.track;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.mywatchmate.live.PresenceTracker;
import com.mywatchmate.live.dto.OnlineViewer;
import com.mywatchmate.watch.content.ResolvedLink;
import com.mywatchmate.watch.content.VideoMetadata;
import com.mywatchmate.watch.content.VideoMetadataClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

/**
 * Attendance, streaks and notes over HTTP against the real database (rolled back). The clock is
 * controlled and the attendance job is run by hand; "having the room open" is fed straight into
 * presence, as the WebSocket listener would.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class TrackFlowTests {

    @Autowired MockMvc mvc;
    @Autowired AttendanceJob attendanceJob;
    @Autowired PresenceTracker presence;
    @MockitoBean Clock clock;
    @MockitoBean VideoMetadataClient metadata;

    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.now().truncatedTo(ChronoUnit.SECONDS));
    private final List<String> openConnections = new ArrayList<>();

    @BeforeEach
    void controlledWorld() {
        when(clock.instant()).thenAnswer(i -> now.get());
        when(metadata.fetch(any(ResolvedLink.class))).thenReturn(new VideoMetadata("Morning Prayer", null, null));
    }

    @AfterEach
    void closeConnections() {
        openConnections.forEach(presence::disconnected);
    }

    @Test
    void attendanceAndStreaksFromWatchingTogether() throws Exception {
        String ada = register("Ada");
        String tunde = register("Tunde");
        String kemi = register("Kemi");
        String spaceId = createRoom(ada);
        String invite = inviteOf(ada, spaceId);
        join(tunde, invite);
        join(kemi, invite);
        String bisi = guestToken(invite, "Bisi");
        advance(Duration.ofMinutes(1));

        // Day 1: a 20-minute session. Ada and Tunde stay throughout, Bisi leaves after 5 minutes, Kemi never comes
        String day1 = startSession(ada, spaceId);
        open(spaceId, ada, "Ada");
        open(spaceId, ada, "Tunde");
        String bisiConnection = open(spaceId, ada, "Bisi");
        watch(Duration.ofMinutes(5));
        presence.disconnected(bisiConnection);
        watch(Duration.ofMinutes(15));

        // While live: watch time grows, nobody is decided yet
        mvc.perform(auth(get("/api/v1/spaces/" + spaceId + "/sessions/" + day1 + "/attendance"), bisi))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("LIVE"))
            .andExpect(jsonPath("$.presentCount").value(3))
            .andExpect(jsonPath("$.requiredSeconds").doesNotExist())
            .andExpect(jsonPath("$.people[0].watchedSeconds").value(1170))
            .andExpect(jsonPath("$.people[0].counted").doesNotExist());

        endSession(ada, spaceId, day1);
        mvc.perform(auth(get("/api/v1/spaces/" + spaceId + "/sessions/" + day1 + "/attendance"), tunde))
            .andExpect(jsonPath("$.status").value("ENDED"))
            .andExpect(jsonPath("$.requiredSeconds").value(600))
            .andExpect(jsonPath("$.attendedCount").value(2))
            .andExpect(jsonPath("$.people[?(@.displayName == 'Ada')].counted").value(true))
            .andExpect(jsonPath("$.people[?(@.displayName == 'Tunde')].counted").value(true))
            .andExpect(jsonPath("$.people[?(@.displayName == 'Bisi')].counted").value(false))
            .andExpect(jsonPath("$.people[?(@.displayName == 'Kemi')]").isEmpty());
        streakIs(ada, 1, 1);
        streakIs(tunde, 1, 1);
        streakIs(kemi, 0, 0);
        mvc.perform(auth(get("/api/v1/spaces/" + spaceId), ada)).andExpect(jsonPath("$.myStreak").value(1));
        mvc.perform(auth(get("/api/v1/spaces/" + spaceId), bisi)).andExpect(jsonPath("$.myStreak").doesNotExist());

        // Day 2: only Ada and Bisi come
        advance(Duration.ofDays(1));
        String day2 = startSession(ada, spaceId);
        open(spaceId, ada, "Ada");
        open(spaceId, ada, "Bisi");
        watch(Duration.ofMinutes(30));
        endSession(ada, spaceId, day2);
        streakIs(ada, 2, 2);
        streakIs(tunde, 0, 1);

        mvc.perform(auth(get("/api/v1/me/attendance"), tunde))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].sessionId").value(day1))
            .andExpect(jsonPath("$[0].spaceTitle").value("Prayer Room"))
            .andExpect(jsonPath("$[0].counted").value(true));

        // Hosts' summary: who's coming, who's drifting
        mvc.perform(auth(get("/api/v1/spaces/" + spaceId + "/attendance"), ada))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.sessions.length()").value(2))
            .andExpect(jsonPath("$.sessions[0].attendedCount").value(2))
            .andExpect(jsonPath("$.sessions[0].expectedCount").value(4))
            .andExpect(jsonPath("$.sessions[1].attendedCount").value(2))
            .andExpect(jsonPath("$.members[?(@.displayName == 'Ada')].attended").value(2))
            .andExpect(jsonPath("$.members[?(@.displayName == 'Kemi')].attended").value(0))
            .andExpect(jsonPath("$.members[?(@.displayName == 'Kemi')].expected").value(2));
        mvc.perform(auth(get("/api/v1/spaces/" + spaceId + "/attendance"), tunde))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("FORBIDDEN_ROLE"));

        // Bisi signs up: the session she counted for as a guest starts her streak
        String bisiUser = read(mvc.perform(json(post("/api/v1/auth/upgrade"), bisi, """
                {"email":"bisi-%s@example.com","password":"correct-horse-battery","displayName":"Bisi"}"""
                .formatted(UUID.randomUUID())))
            .andExpect(status().isCreated()).andReturn(), "$.accessToken");
        streakIs(bisiUser, 1, 1);
    }

    @Test
    void privateNotesStayPrivateAndSharedOnesAreModerated() throws Exception {
        String ada = register("Ada");
        String tunde = register("Tunde");
        String spaceId = createRoom(ada);
        String invite = inviteOf(ada, spaceId);
        join(tunde, invite);
        String bisi = guestToken(invite, "Bisi");
        String session = startSession(ada, spaceId);

        String secret = read(mvc.perform(json(post("/api/v1/spaces/" + spaceId + "/notes"), tunde, """
                {"body":"Pray for my exam","sessionId":"%s","positionSeconds":750}""".formatted(session)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.visibility").value("PRIVATE"))
            .andExpect(jsonPath("$.sessionId").value(session))
            .andReturn(), "$.id");
        String shared = read(mvc.perform(json(post("/api/v1/spaces/" + spaceId + "/notes"), bisi, """
                {"body":"Amen!","visibility":"SHARED"}"""))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.author.displayName").value("Bisi"))
            .andReturn(), "$.id");

        // The owner sees the shared note only; Tunde sees both
        mvc.perform(auth(get("/api/v1/spaces/" + spaceId + "/notes"), ada))
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].body").value("Amen!"));
        mvc.perform(auth(get("/api/v1/spaces/" + spaceId + "/notes"), tunde))
            .andExpect(jsonPath("$.length()").value(2));
        mvc.perform(auth(get("/api/v1/spaces/" + spaceId + "/notes?sessionId=" + session), tunde))
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].positionSeconds").value(750));
        mvc.perform(auth(get("/api/v1/spaces/" + spaceId + "/notes?mine=true"), bisi))
            .andExpect(jsonPath("$[0].id").value(shared));
        mvc.perform(json(patch("/api/v1/spaces/" + spaceId + "/notes/" + secret), ada, "{\"body\":\"x\"}"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("NOTE_NOT_FOUND"));

        // Only Bisi edits her note; the owner may remove it, Tunde may not
        mvc.perform(json(patch("/api/v1/spaces/" + spaceId + "/notes/" + shared), tunde, "{\"body\":\"x\"}"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("NOT_YOUR_NOTE"));
        mvc.perform(json(patch("/api/v1/spaces/" + spaceId + "/notes/" + shared), bisi, "{\"body\":\"Amen, amen!\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.body").value("Amen, amen!"));
        mvc.perform(auth(delete("/api/v1/spaces/" + spaceId + "/notes/" + shared), tunde))
            .andExpect(status().isForbidden());
        mvc.perform(auth(delete("/api/v1/spaces/" + spaceId + "/notes/" + shared), ada))
            .andExpect(status().isNoContent());
        mvc.perform(auth(get("/api/v1/spaces/" + spaceId + "/notes"), ada))
            .andExpect(jsonPath("$.length()").value(0));

        // Tunde shares his note after all
        mvc.perform(json(patch("/api/v1/spaces/" + spaceId + "/notes/" + secret), tunde, "{\"visibility\":\"SHARED\"}"))
            .andExpect(jsonPath("$.visibility").value("SHARED"));
        mvc.perform(auth(get("/api/v1/spaces/" + spaceId + "/notes"), ada))
            .andExpect(jsonPath("$[0].body").value("Pray for my exam"));
    }

    // ---- helpers -------------------------------------------------------------

    private void advance(Duration duration) {
        now.set(now.get().plus(duration));
    }

    /** Time passes in 30-second steps, with the attendance job running at each one. */
    private void watch(Duration duration) {
        for (long s = 0; s < duration.toSeconds(); s += 30) {
            advance(Duration.ofSeconds(30));
            attendanceJob.tick();
        }
    }

    /** Someone opens the room (as the WebSocket subscription would record it). */
    private String open(String spaceId, String token, String name) throws Exception {
        MvcResult people = mvc.perform(auth(get("/api/v1/spaces/" + spaceId + "/participants"), token)).andReturn();
        List<String> ids = JsonPath.read(people.getResponse().getContentAsString(), "$[?(@.displayName == '" + name + "')].id");
        List<Boolean> guest = JsonPath.read(people.getResponse().getContentAsString(), "$[?(@.displayName == '" + name + "')].guest");
        String connection = "test-" + UUID.randomUUID();
        presence.subscribed(connection, "sub-1", UUID.fromString(spaceId),
            new OnlineViewer(UUID.fromString(ids.get(0)), name, guest.get(0)));
        openConnections.add(connection);
        return connection;
    }

    private String startSession(String token, String spaceId) throws Exception {
        return read(mvc.perform(json(post("/api/v1/spaces/" + spaceId + "/sessions/watch-now"), token, "{\"title\":\"Devotion\"}"))
            .andExpect(status().isCreated()).andReturn(), "$.id");
    }

    private void endSession(String token, String spaceId, String sessionId) throws Exception {
        mvc.perform(auth(post("/api/v1/spaces/" + spaceId + "/sessions/" + sessionId + "/end"), token))
            .andExpect(status().isOk());
        openConnections.forEach(presence::disconnected);
        openConnections.clear();
    }

    private void streakIs(String token, int current, int longest) throws Exception {
        mvc.perform(auth(get("/api/v1/me/streak"), token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.current").value(current))
            .andExpect(jsonPath("$.longest").value(longest));
    }

    private String register(String name) throws Exception {
        return read(mvc.perform(json(post("/api/v1/auth/register"), null, """
                {"email":"%s-%s@example.com","password":"correct-horse-battery","displayName":"%s"}"""
                .formatted(name.toLowerCase(), UUID.randomUUID(), name)))
            .andExpect(status().isCreated()).andReturn(), "$.accessToken");
    }

    private String createRoom(String token) throws Exception {
        return read(mvc.perform(json(post("/api/v1/spaces"), token, """
                {"title":"Prayer Room","audienceType":"DIASPORA","videoUrl":"https://youtu.be/dQw4w9WgXcQ"}"""))
            .andExpect(status().isCreated()).andReturn(), "$.id");
    }

    private String inviteOf(String token, String spaceId) throws Exception {
        return read(mvc.perform(auth(get("/api/v1/spaces/" + spaceId + "/invite"), token)).andReturn(), "$.inviteToken");
    }

    private void join(String token, String invite) throws Exception {
        mvc.perform(auth(post("/api/v1/invites/" + invite + "/join"), token)).andExpect(status().isOk());
    }

    private String guestToken(String invite, String name) throws Exception {
        return read(mvc.perform(json(post("/api/v1/auth/guest"), null, """
                {"inviteToken":"%s","displayName":"%s"}""".formatted(invite, name)))
            .andExpect(status().isCreated()).andReturn(), "$.accessToken");
    }

    private static MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder request, String token) {
        return token == null ? request : request.header("Authorization", "Bearer " + token);
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String token, String body) {
        return auth(request, token).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static String read(MvcResult result, String path) throws Exception {
        return JsonPath.read(result.getResponse().getContentAsString(), path).toString();
    }
}
