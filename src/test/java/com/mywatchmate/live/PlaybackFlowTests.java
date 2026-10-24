package com.mywatchmate.live;

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
import com.mywatchmate.watch.content.ResolvedLink;
import com.mywatchmate.watch.content.VideoMetadata;
import com.mywatchmate.watch.content.VideoMetadataClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

/**
 * Live sync over HTTP against the real database (rolled back). The clock is controlled so positions
 * can be checked exactly; YouTube is faked and says every video is 10 minutes long.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class PlaybackFlowTests {

    private static final String VIDEO = "https://youtu.be/dQw4w9WgXcQ";

    @Autowired MockMvc mvc;
    @MockitoBean VideoMetadataClient metadata;
    @MockitoBean Clock clock;

    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.now());

    @BeforeEach
    void setUp() {
        when(clock.instant()).thenAnswer(i -> now.get());
        when(metadata.fetch(any(ResolvedLink.class))).thenReturn(new VideoMetadata("Movie", null, 600));
    }

    private void advance(Duration duration) {
        now.set(now.get().plus(duration));
    }

    @Test
    void theWholeSharedPlaybackStory() throws Exception {
        String ada = register("Ada");
        String tunde = register("Tunde");

        // A friends room with a video: playing from 0 straight away, and members may control it
        MvcResult created = mvc.perform(json(post("/api/v1/spaces"), ada, """
                {"title":"Movie night","audienceType":"FRIENDS","videoUrl":"%s"}""".formatted(VIDEO)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.membersCanControlPlayback").value(true))
            .andExpect(jsonPath("$.canControlPlayback").value(true))
            .andExpect(jsonPath("$.playback.status").value("PLAYING"))
            .andExpect(jsonPath("$.playback.positionMs").value(0))
            .andExpect(jsonPath("$.playback.durationMs").value(600_000))
            .andExpect(jsonPath("$.onlineCount").value(0))
            .andReturn();
        String spaceId = read(created, "$.id");
        String contentId = read(created, "$.nowPlaying.id");
        mvc.perform(auth(post("/api/v1/invites/" + inviteOf(ada, spaceId) + "/join"), tunde)).andExpect(status().isOk());

        // 90 seconds later, a late joiner lands on the right moment
        advance(Duration.ofSeconds(90));
        mvc.perform(auth(get("/api/v1/spaces/" + spaceId + "/playback"), tunde))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.contentId").value(contentId))
            .andExpect(jsonPath("$.status").value("PLAYING"))
            .andExpect(jsonPath("$.positionMs").value(90_000))
            .andExpect(jsonPath("$.serverTimeMs").value(now.get().toEpochMilli()));

        // Tunde pauses, then seeks; it stays where he left it
        long v1 = Long.parseLong(read(press(tunde, spaceId, "{\"action\":\"PAUSE\"}")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("PAUSED"))
            .andExpect(jsonPath("$.positionMs").value(90_000))
            .andExpect(jsonPath("$.updatedBy.displayName").value("Tunde"))
            .andReturn(), "$.version"));
        advance(Duration.ofSeconds(5));
        long v2 = Long.parseLong(read(press(tunde, spaceId, "{\"action\":\"SEEK\",\"positionMs\":540000}")
            .andExpect(jsonPath("$.status").value("PAUSED"))
            .andExpect(jsonPath("$.positionMs").value(540_000))
            .andReturn(), "$.version"));
        assertThat(v2).isGreaterThan(v1);
        advance(Duration.ofSeconds(30));
        mvc.perform(auth(get("/api/v1/spaces/" + spaceId), ada))
            .andExpect(jsonPath("$.playback.positionMs").value(540_000))
            .andExpect(jsonPath("$.playback.version").value(v2));

        // Ada plays: after 2 minutes the video has run out and shows paused at its end
        press(ada, spaceId, "{\"action\":\"PLAY\"}").andExpect(jsonPath("$.status").value("PLAYING"));
        advance(Duration.ofMinutes(2));
        mvc.perform(auth(get("/api/v1/spaces/" + spaceId + "/playback"), ada))
            .andExpect(jsonPath("$.status").value("PAUSED"))
            .andExpect(jsonPath("$.positionMs").value(600_000));
        press(ada, spaceId, "{\"action\":\"SEEK\",\"positionMs\":600001}")
            .andExpect(status().isUnprocessableContent())
            .andExpect(jsonPath("$.code").value("POSITION_OUT_OF_RANGE"));
        press(ada, spaceId, "{\"action\":\"PLAY\"}")
            .andExpect(jsonPath("$.status").value("PLAYING"))
            .andExpect(jsonPath("$.positionMs").value(0));

        // Removing what's playing stops it for everyone
        mvc.perform(auth(delete("/api/v1/spaces/" + spaceId + "/content/" + contentId), ada))
            .andExpect(status().isNoContent());
        mvc.perform(auth(get("/api/v1/spaces/" + spaceId + "/playback"), tunde))
            .andExpect(jsonPath("$.status").value("STOPPED"))
            .andExpect(jsonPath("$.contentId").doesNotExist());
        press(ada, spaceId, "{\"action\":\"PLAY\"}")
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("NOTHING_PLAYING"));

        // A new video starts from the beginning
        mvc.perform(json(post("/api/v1/spaces/" + spaceId + "/content"), ada, "{\"url\":\"https://youtu.be/aaaaaaaaaaa\"}"))
            .andExpect(status().isCreated());
        mvc.perform(auth(get("/api/v1/spaces/" + spaceId + "/playback"), tunde))
            .andExpect(jsonPath("$.status").value("PLAYING"))
            .andExpect(jsonPath("$.positionMs").value(0));
    }

    @Test
    void inHostLedRoomsMembersWatchAndHostsPress() throws Exception {
        String ada = register("Ada");
        String tunde = register("Tunde");
        String spaceId = read(mvc.perform(json(post("/api/v1/spaces"), ada, """
                {"title":"6am Prayer","audienceType":"DIASPORA","videoUrl":"%s"}""".formatted(VIDEO)))
            .andExpect(jsonPath("$.membersCanControlPlayback").value(false))
            .andReturn(), "$.id");
        String invite = inviteOf(ada, spaceId);
        mvc.perform(auth(post("/api/v1/invites/" + invite + "/join"), tunde))
            .andExpect(jsonPath("$.canControlPlayback").value(false));
        String bisi = guestToken(invite, "Bisi");

        press(tunde, spaceId, "{\"action\":\"PAUSE\"}")
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("FORBIDDEN_ROLE"));
        press(bisi, spaceId, "{\"action\":\"PAUSE\"}")
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.detail").value("Guests can't control playback. Create an account to continue."));
        mvc.perform(auth(get("/api/v1/spaces/" + spaceId + "/playback"), bisi))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("PLAYING"));

        // The owner hands members the remote
        mvc.perform(json(patch("/api/v1/spaces/" + spaceId), ada, "{\"membersCanControlPlayback\":true}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.membersCanControlPlayback").value(true));
        press(tunde, spaceId, "{\"action\":\"PAUSE\"}").andExpect(status().isOk());
        press(bisi, spaceId, "{\"action\":\"PLAY\"}").andExpect(status().isForbidden());
    }

    @Test
    void liveStreamsPlayAndPauseButDontSeek() throws Exception {
        String ada = register("Ada");
        String spaceId = read(mvc.perform(json(post("/api/v1/spaces"), ada, """
                {"title":"Sunday service","audienceType":"DIASPORA","videoUrl":"https://www.youtube.com/live/abcdefghijk"}"""))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.playback.live").value(true))
            .andExpect(jsonPath("$.playback.durationMs").doesNotExist())
            .andReturn(), "$.id");

        press(ada, spaceId, "{\"action\":\"SEEK\",\"positionMs\":1000}")
            .andExpect(status().isUnprocessableContent())
            .andExpect(jsonPath("$.code").value("LIVE_CANNOT_SEEK"));
        press(ada, spaceId, "{\"action\":\"PAUSE\"}").andExpect(status().isOk());
    }

    @Test
    void mashingTheButtonsIsSlowedDown() throws Exception {
        String ada = register("Ada");
        String spaceId = read(mvc.perform(json(post("/api/v1/spaces"), ada, """
                {"title":"Room","audienceType":"FRIENDS","videoUrl":"%s"}""".formatted(VIDEO))).andReturn(), "$.id");

        for (int i = 0; i < 5; i++) {
            press(ada, spaceId, "{\"action\":\"PAUSE\"}").andExpect(status().isOk());
        }
        press(ada, spaceId, "{\"action\":\"PLAY\"}")
            .andExpect(status().isTooManyRequests())
            .andExpect(jsonPath("$.code").value("PLAYBACK_RATE_LIMITED"));
        advance(Duration.ofSeconds(1));
        press(ada, spaceId, "{\"action\":\"PLAY\"}").andExpect(status().isOk());
    }

    @Test
    void outsidersSeeNothingAndTheClockIsForEveryone() throws Exception {
        String ada = register("Ada");
        String kemi = register("Kemi");
        String spaceId = read(mvc.perform(json(post("/api/v1/spaces"), ada, """
                {"title":"Private","audienceType":"COUPLE"}""")).andReturn(), "$.id");

        mvc.perform(auth(get("/api/v1/spaces/" + spaceId + "/playback"), kemi))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("SPACE_NOT_FOUND"));
        mvc.perform(auth(get("/api/v1/spaces/" + spaceId + "/presence"), kemi))
            .andExpect(status().isNotFound());
        press(kemi, spaceId, "{\"action\":\"PLAY\"}").andExpect(status().isNotFound());
        mvc.perform(auth(get("/api/v1/spaces/" + spaceId + "/presence"), ada))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.onlineCount").value(0));
        mvc.perform(auth(get("/api/v1/spaces/" + spaceId + "/playback"), ada))
            .andExpect(jsonPath("$.status").value("STOPPED"));

        mvc.perform(get("/api/v1/time"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.serverTimeMs").value(now.get().toEpochMilli()));
    }

    // ---- helpers -------------------------------------------------------------

    private ResultActions press(String token, String spaceId, String body)
        throws Exception {
        return mvc.perform(json(post("/api/v1/spaces/" + spaceId + "/playback"), token, body));
    }

    private String register(String name) throws Exception {
        return read(mvc.perform(json(post("/api/v1/auth/register"), null, """
                {"email":"%s-%s@example.com","password":"correct-horse-battery","displayName":"%s"}"""
                .formatted(name.toLowerCase(), UUID.randomUUID(), name)))
            .andExpect(status().isCreated()).andReturn(), "$.accessToken");
    }

    private String inviteOf(String token, String spaceId) throws Exception {
        return read(mvc.perform(auth(get("/api/v1/spaces/" + spaceId + "/invite"), token)).andReturn(), "$.inviteToken");
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
