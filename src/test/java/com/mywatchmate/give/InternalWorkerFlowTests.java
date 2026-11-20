package com.mywatchmate.give;

import static org.hamcrest.Matchers.hasItem;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.mywatchmate.watch.content.ResolvedLink;
import com.mywatchmate.watch.content.VideoMetadata;
import com.mywatchmate.watch.content.VideoMetadataClient;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

/** What the AI cue worker sees, over HTTP with the real database (rolled back). */
@SpringBootTest(properties = "mywatchmate.give.internal-token=test-worker-token")
@AutoConfigureMockMvc
@Transactional
class InternalWorkerFlowTests {

    private static final String WORKER = "test-worker-token";

    @Autowired MockMvc mvc;
    @MockitoBean VideoMetadataClient metadata;

    @Test
    void theWorkerSeesLiveRoomsAndCanReadOnlyDirectStreams() throws Exception {
        when(metadata.fetch(any(ResolvedLink.class))).thenReturn(new VideoMetadata("Service", null, null));
        String ada = register();
        String stream = "https://cdn.church.org/live-" + UUID.randomUUID().toString().substring(0, 8) + ".m3u8";
        String streamRoom = createRoom(ada, stream);
        String youtubeRoom = createRoom(ada, "https://youtu.be/dQw4w9WgXcQ");
        String streamSession = startSession(ada, streamRoom);
        String youtubeSession = startSession(ada, youtubeRoom);

        mvc.perform(get("/api/v1/internal/live-sessions").header("X-Service-Token", WORKER))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.sessionId == '" + streamSession + "')].streamUrl").value(hasItem(stream)))
            .andExpect(jsonPath("$[?(@.sessionId == '" + streamSession + "')].spaceId").value(hasItem(streamRoom)))
            .andExpect(jsonPath("$[?(@.sessionId == '" + youtubeSession + "')].source").value(hasItem("YOUTUBE")))
            .andExpect(jsonPath("$[?(@.sessionId == '" + youtubeSession + "')].streamUrl").value(hasItem((Object) null)))
            .andExpect(jsonPath("$[?(@.sessionId == '" + youtubeSession + "')].screenShare").value(hasItem(false)))
            .andExpect(jsonPath("$[?(@.sessionId == '" + streamSession + "')].playback.status").value(hasItem("PLAYING")))
            .andExpect(jsonPath("$[?(@.sessionId == '" + streamSession + "')].playback.version").exists());

        // Calls aren't set up in tests: no way into a call to watch
        mvc.perform(post("/api/v1/internal/sessions/" + youtubeSession + "/watch-token").header("X-Service-Token", WORKER))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.code").value("CALLS_DISABLED"));
    }

    @Test
    void onlyOurWorkerGetsIn() throws Exception {
        mvc.perform(get("/api/v1/internal/live-sessions")).andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("INVALID_SERVICE_TOKEN"));
        mvc.perform(get("/api/v1/internal/live-sessions").header("X-Service-Token", "guess")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/internal/live-sessions").header("Authorization", "Bearer " + register()))
            .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/internal/sessions/" + UUID.randomUUID() + "/watch-token").header("X-Service-Token", "guess"))
            .andExpect(status().isUnauthorized());
    }

    // ---- helpers -------------------------------------------------------------

    private String register() throws Exception {
        return read(json(post("/api/v1/auth/register"), null, """
                {"email":"%s@example.com","password":"correct-horse-battery","displayName":"Ada"}"""
                .formatted("worker-" + UUID.randomUUID())).andExpect(status().isCreated()), "$.accessToken");
    }

    private String createRoom(String token, String video) throws Exception {
        return read(json(post("/api/v1/spaces"), token, """
                {"title":"Sunday","audienceType":"DIASPORA","videoUrl":"%s"}""".formatted(video))
            .andExpect(status().isCreated()), "$.id");
    }

    private String startSession(String token, String room) throws Exception {
        return read(json(post("/api/v1/spaces/" + room + "/sessions/watch-now"), token, "{}")
            .andExpect(status().isCreated()), "$.id");
    }

    private ResultActions json(MockHttpServletRequestBuilder request, String token, String body) throws Exception {
        MockHttpServletRequestBuilder withBody = request.contentType(MediaType.APPLICATION_JSON).content(body);
        return mvc.perform(token == null ? withBody : withBody.header("Authorization", "Bearer " + token));
    }

    private static String read(ResultActions result, String path) throws Exception {
        return JsonPath.read(result.andReturn().getResponse().getContentAsString(), path).toString();
    }
}
