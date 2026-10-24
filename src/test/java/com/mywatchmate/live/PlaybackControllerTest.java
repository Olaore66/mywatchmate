package com.mywatchmate.live;

import static com.mywatchmate.web.WebLayerTestConfig.asGuest;
import static com.mywatchmate.web.WebLayerTestConfig.asUser;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mywatchmate.auth.AuthPrincipal;
import com.mywatchmate.common.ApiException;
import com.mywatchmate.live.PlaybackState.Status;
import com.mywatchmate.live.dto.OnlineViewer;
import com.mywatchmate.live.dto.PlaybackCommand.Action;
import com.mywatchmate.live.dto.PlaybackResponse;
import com.mywatchmate.watch.SpaceAccess;
import com.mywatchmate.watch.dto.PersonRef;
import com.mywatchmate.web.WebLayerTestConfig;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest({PlaybackController.class, TimeController.class})
@Import(WebLayerTestConfig.class)
class PlaybackControllerTest {

    private static final UUID USER = UUID.randomUUID();
    private static final UUID SPACE = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-11-02T00:00:00Z");

    @Autowired MockMvc mvc;
    @MockitoBean PlaybackService playback;
    @MockitoBean PresenceTracker presence;
    @MockitoBean SpaceAccess access;
    @MockitoBean Clock clock;

    private static PlaybackResponse paused(long positionMs) {
        return new PlaybackResponse(UUID.randomUUID(), Status.PAUSED, positionMs, NOW.toEpochMilli(), 600_000L, false, 7,
            new PersonRef(UUID.randomUUID(), "Ada"));
    }

    @Test
    void theClockIsPublic() throws Exception {
        when(clock.instant()).thenReturn(NOW);

        mvc.perform(get("/api/v1/time"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.serverTimeMs").value(NOW.toEpochMilli()))
            .andExpect(jsonPath("$.serverTime").value("2026-11-02T00:00:00Z"));
    }

    @Test
    void guestsCanReadWhereTheVideoIs() throws Exception {
        UUID bisi = UUID.randomUUID();
        when(playback.get(AuthPrincipal.guest(bisi, SPACE), SPACE)).thenReturn(paused(42_000));

        mvc.perform(get("/api/v1/spaces/" + SPACE + "/playback").with(asGuest(bisi, SPACE)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("PAUSED"))
            .andExpect(jsonPath("$.positionMs").value(42_000))
            .andExpect(jsonPath("$.serverTimeMs").value(NOW.toEpochMilli()))
            .andExpect(jsonPath("$.durationMs").value(600_000))
            .andExpect(jsonPath("$.version").value(7))
            .andExpect(jsonPath("$.updatedBy.displayName").value("Ada"));
    }

    @Test
    void pressingSeekSendsThePosition() throws Exception {
        when(playback.command(eq(AuthPrincipal.user(USER)), eq(SPACE), any())).thenReturn(paused(754_000));

        mvc.perform(post("/api/v1/spaces/" + SPACE + "/playback").with(asUser(USER))
                .contentType(MediaType.APPLICATION_JSON).content("{\"action\":\"SEEK\",\"positionMs\":754000}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.positionMs").value(754_000));
        verify(playback).command(eq(AuthPrincipal.user(USER)), eq(SPACE),
            argThat(c -> c.action() == Action.SEEK && c.positionMs() == 754_000L));
    }

    @Test
    void badCommandsAreRejectedBeforeTheService() throws Exception {
        mvc.perform(post("/api/v1/spaces/" + SPACE + "/playback").with(asUser(USER))
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
            .andExpect(jsonPath("$.errors.action").exists());
        mvc.perform(post("/api/v1/spaces/" + SPACE + "/playback").with(asUser(USER))
                .contentType(MediaType.APPLICATION_JSON).content("{\"action\":\"SEEK\",\"positionMs\":-5}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errors.positionMs").exists());
        mvc.perform(post("/api/v1/spaces/" + SPACE + "/playback").with(asUser(USER))
                .contentType(MediaType.APPLICATION_JSON).content("{\"action\":\"REWIND\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
        verifyNoInteractions(playback);
    }

    @Test
    void serviceRefusalsKeepTheirCodes() throws Exception {
        when(playback.command(any(), eq(SPACE), any()))
            .thenThrow(ApiException.forbidden("FORBIDDEN_ROLE", "Only the owner or admins can control playback in this room."))
            .thenThrow(new ApiException(HttpStatus.TOO_MANY_REQUESTS, "PLAYBACK_RATE_LIMITED", "Too many"));

        mvc.perform(post("/api/v1/spaces/" + SPACE + "/playback").with(asUser(USER))
                .contentType(MediaType.APPLICATION_JSON).content("{\"action\":\"PLAY\"}"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("FORBIDDEN_ROLE"));
        mvc.perform(post("/api/v1/spaces/" + SPACE + "/playback").with(asUser(USER))
                .contentType(MediaType.APPLICATION_JSON).content("{\"action\":\"PLAY\"}"))
            .andExpect(status().isTooManyRequests())
            .andExpect(jsonPath("$.code").value("PLAYBACK_RATE_LIMITED"));
    }

    @Test
    void membersSeeWhoIsOnline() throws Exception {
        when(access.isActiveMember(AuthPrincipal.user(USER), SPACE)).thenReturn(true);
        when(presence.online(SPACE)).thenReturn(List.of(
            new OnlineViewer(UUID.randomUUID(), "Ada", false), new OnlineViewer(UUID.randomUUID(), "Bisi", true)));

        mvc.perform(get("/api/v1/spaces/" + SPACE + "/presence").with(asUser(USER)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.onlineCount").value(2))
            .andExpect(jsonPath("$.online[1].displayName").value("Bisi"))
            .andExpect(jsonPath("$.online[1].guest").value(true));
    }

    @Test
    void outsidersDontLearnWhoIsOnline() throws Exception {
        when(access.isActiveMember(AuthPrincipal.user(USER), SPACE)).thenReturn(false);

        mvc.perform(get("/api/v1/spaces/" + SPACE + "/presence").with(asUser(USER)))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("SPACE_NOT_FOUND"));
        verifyNoInteractions(presence);
    }

    @Test
    void playbackNeedsSignIn() throws Exception {
        mvc.perform(get("/api/v1/spaces/" + SPACE + "/playback"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }
}
