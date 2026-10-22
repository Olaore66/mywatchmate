package com.mywatchmate.watch;

import static com.mywatchmate.web.WebLayerTestConfig.asGuest;
import static com.mywatchmate.web.WebLayerTestConfig.asUser;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mywatchmate.auth.AuthPrincipal;
import com.mywatchmate.common.ApiException;
import com.mywatchmate.watch.dto.ContentResponse;
import com.mywatchmate.watch.dto.LinkPreviewResponse;
import com.mywatchmate.watch.dto.PersonRef;
import com.mywatchmate.web.WebLayerTestConfig;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ContentController.class)
@Import(WebLayerTestConfig.class)
class ContentControllerTest {

    private static final UUID USER = UUID.randomUUID();
    private static final UUID SPACE = UUID.randomUUID();
    private static final String VIDEO = "https://youtu.be/dQw4w9WgXcQ";

    @Autowired MockMvc mvc;
    @MockitoBean ContentService content;

    @Test
    void previewReturnsWhatTheLinkIs() throws Exception {
        when(content.preview(VIDEO)).thenReturn(new LinkPreviewResponse(ContentItem.Source.YOUTUBE,
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ", "dQw4w9WgXcQ", "Morning Prayer", null, null, false));

        mvc.perform(post("/api/v1/content/preview").with(asUser(USER)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"url\":\"" + VIDEO + "\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.source").value("YOUTUBE"))
            .andExpect(jsonPath("$.title").value("Morning Prayer"));
    }

    @Test
    void unplayableLinkIs422WithItsCode() throws Exception {
        when(content.preview("https://tiktok.com/x"))
            .thenThrow(ApiException.unprocessable("UNSUPPORTED_LINK", "We can only play YouTube and Vimeo links…"));

        mvc.perform(post("/api/v1/content/preview").with(asUser(USER)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"url\":\"https://tiktok.com/x\"}"))
            .andExpect(status().isUnprocessableContent())
            .andExpect(jsonPath("$.code").value("UNSUPPORTED_LINK"));
    }

    @Test
    void blankOrMissingUrlIsRejectedBeforeTheService() throws Exception {
        mvc.perform(post("/api/v1/content/preview").with(asUser(USER)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"url\":\" \"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errors.url").exists());
        mvc.perform(post("/api/v1/spaces/" + SPACE + "/content").with(asUser(USER)).contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isBadRequest());
        verifyNoInteractions(content);
    }

    @Test
    void addingIs201AndPlayNowDefaultsToUnset() throws Exception {
        when(content.add(eq(AuthPrincipal.user(USER)), eq(SPACE), any())).thenReturn(video());

        mvc.perform(post("/api/v1/spaces/" + SPACE + "/content").with(asUser(USER)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"url\":\"" + VIDEO + "\"}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.addedBy.displayName").value("Ada"));
        verify(content).add(eq(AuthPrincipal.user(USER)), eq(SPACE), argThat(r -> r.url().equals(VIDEO) && r.playNow() == null));
    }

    @Test
    void switchingStoppingAndRemoving() throws Exception {
        UUID videoId = UUID.randomUUID();
        when(content.playNow(AuthPrincipal.user(USER), SPACE, videoId)).thenReturn(video());

        mvc.perform(put("/api/v1/spaces/" + SPACE + "/now-playing").with(asUser(USER)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"contentId\":\"" + videoId + "\"}"))
            .andExpect(status().isOk());
        mvc.perform(delete("/api/v1/spaces/" + SPACE + "/now-playing").with(asUser(USER))).andExpect(status().isNoContent());
        mvc.perform(delete("/api/v1/spaces/" + SPACE + "/content/" + videoId).with(asUser(USER))).andExpect(status().isNoContent());

        verify(content).stopPlaying(AuthPrincipal.user(USER), SPACE);
        verify(content).remove(AuthPrincipal.user(USER), SPACE, videoId);
    }

    @Test
    void switchingNeedsAContentId() throws Exception {
        mvc.perform(put("/api/v1/spaces/" + SPACE + "/now-playing").with(asUser(USER)).contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errors.contentId").exists());
    }

    @Test
    void guestsCanListTheRoomsVideos() throws Exception {
        UUID guest = UUID.randomUUID();
        when(content.list(AuthPrincipal.guest(guest, SPACE), SPACE)).thenReturn(List.of(video()));

        mvc.perform(get("/api/v1/spaces/" + SPACE + "/content").with(asGuest(guest, SPACE)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void everythingNeedsSignIn() throws Exception {
        mvc.perform(post("/api/v1/content/preview").contentType(MediaType.APPLICATION_JSON).content("{\"url\":\"" + VIDEO + "\"}"))
            .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/spaces/" + SPACE + "/content")).andExpect(status().isUnauthorized());
        verifyNoInteractions(content);
    }

    private static ContentResponse video() {
        return new ContentResponse(UUID.randomUUID(), ContentItem.Source.YOUTUBE, "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
            "dQw4w9WgXcQ", "Morning Prayer", null, null, false, new PersonRef(UUID.randomUUID(), "Ada"), Instant.now());
    }
}
