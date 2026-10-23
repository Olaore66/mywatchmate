package com.mywatchmate.sessions;

import static com.mywatchmate.web.WebLayerTestConfig.asGuest;
import static com.mywatchmate.web.WebLayerTestConfig.asUser;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mywatchmate.auth.AuthPrincipal;
import com.mywatchmate.common.ApiException;
import com.mywatchmate.sessions.dto.SessionResponse;
import com.mywatchmate.watch.WatchSession;
import com.mywatchmate.web.WebLayerTestConfig;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(SessionController.class)
@Import(WebLayerTestConfig.class)
class SessionControllerTest {

    private static final UUID USER = UUID.randomUUID();
    private static final UUID SPACE = UUID.randomUUID();
    private static final String BASE = "/api/v1/spaces/" + SPACE + "/sessions";

    @Autowired MockMvc mvc;
    @MockitoBean SessionService sessions;

    @Test
    void listShowsUtcAndLocalTimes() throws Exception {
        when(sessions.list(AuthPrincipal.user(USER), SPACE, null, null)).thenReturn(List.of(session()));

        mvc.perform(get(BASE).with(asUser(USER)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].scheduledAt").value("2026-11-02T05:00:00Z"))
            .andExpect(jsonPath("$[0].scheduledAtLocal").value("2026-11-02T06:00:00+01:00"))
            .andExpect(jsonPath("$[0].localTimezone").value("Africa/Lagos"))
            .andExpect(jsonPath("$[0].status").value("SCHEDULED"));
    }

    @Test
    void historyWindowIsPassedThrough() throws Exception {
        Instant from = Instant.parse("2026-10-01T00:00:00Z");
        Instant to = Instant.parse("2026-11-01T00:00:00Z");
        when(sessions.list(AuthPrincipal.user(USER), SPACE, from, to)).thenReturn(List.of());

        mvc.perform(get(BASE + "?from=2026-10-01T00:00:00Z&to=2026-11-01T00:00:00Z").with(asUser(USER)))
            .andExpect(status().isOk());
        verify(sessions).list(AuthPrincipal.user(USER), SPACE, from, to);
    }

    @Test
    void upcomingIsForRegisteredUsers() throws Exception {
        when(sessions.upcomingForUser(AuthPrincipal.user(USER))).thenReturn(List.of(session()));

        mvc.perform(get("/api/v1/me/upcoming").with(asUser(USER))).andExpect(status().isOk())
            .andExpect(jsonPath("$[0].spaceTitle").value("6am Prayer Room"));
        mvc.perform(get("/api/v1/me/upcoming").with(asGuest(UUID.randomUUID(), SPACE))).andExpect(status().isForbidden());
    }

    @Test
    void oneOffNeedsAFutureTime() throws Exception {
        mvc.perform(post(BASE).with(asUser(USER)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"scheduledAt\":\"2020-01-01T00:00:00Z\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errors.scheduledAt").exists());
        verifyNoInteractions(sessions);

        when(sessions.createOneOff(eq(AuthPrincipal.user(USER)), eq(SPACE), any())).thenReturn(session());
        mvc.perform(post(BASE).with(asUser(USER)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"scheduledAt\":\"2099-01-01T19:00:00Z\",\"title\":\"Film night\"}"))
            .andExpect(status().isCreated());
        verify(sessions).createOneOff(eq(AuthPrincipal.user(USER)), eq(SPACE),
            argThat(r -> r.scheduledAt().equals(Instant.parse("2099-01-01T19:00:00Z")) && r.title().equals("Film night")));
    }

    @Test
    void watchNowWorksWithOrWithoutABody() throws Exception {
        when(sessions.watchNow(eq(AuthPrincipal.user(USER)), eq(SPACE), any())).thenReturn(session());

        mvc.perform(post(BASE + "/watch-now").with(asUser(USER))).andExpect(status().isCreated());
        verify(sessions).watchNow(eq(AuthPrincipal.user(USER)), eq(SPACE), isNull());

        mvc.perform(post(BASE + "/watch-now").with(asUser(USER)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Catch-up\"}"))
            .andExpect(status().isCreated());
    }

    @Test
    void alreadyLiveIs409() throws Exception {
        when(sessions.watchNow(eq(AuthPrincipal.user(USER)), eq(SPACE), any()))
            .thenThrow(ApiException.conflict("SESSION_ALREADY_LIVE", "A session is already live in this room."));

        mvc.perform(post(BASE + "/watch-now").with(asUser(USER)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("SESSION_ALREADY_LIVE"));
    }

    @Test
    void startEndCancel() throws Exception {
        UUID id = UUID.randomUUID();
        AuthPrincipal ada = AuthPrincipal.user(USER);
        when(sessions.start(ada, SPACE, id)).thenReturn(session());
        when(sessions.end(ada, SPACE, id)).thenReturn(session());
        when(sessions.cancel(ada, SPACE, id)).thenReturn(session());

        mvc.perform(post(BASE + "/" + id + "/start").with(asUser(USER))).andExpect(status().isOk());
        mvc.perform(post(BASE + "/" + id + "/end").with(asUser(USER))).andExpect(status().isOk());
        mvc.perform(post(BASE + "/" + id + "/cancel").with(asUser(USER))).andExpect(status().isOk());

        verify(sessions).start(ada, SPACE, id);
        verify(sessions).end(ada, SPACE, id);
        verify(sessions).cancel(ada, SPACE, id);
    }

    @Test
    void signInRequired() throws Exception {
        mvc.perform(get(BASE)).andExpect(status().isUnauthorized());
        mvc.perform(post(BASE + "/watch-now")).andExpect(status().isUnauthorized());
        verifyNoInteractions(sessions);
    }

    private static SessionResponse session() {
        return new SessionResponse(UUID.randomUUID(), SPACE, "6am Prayer Room", null, "Morning Devotion",
            WatchSession.Status.SCHEDULED, Instant.parse("2026-11-02T05:00:00Z"),
            OffsetDateTime.parse("2026-11-02T06:00:00+01:00"), "Africa/Lagos", null, null, null);
    }
}
