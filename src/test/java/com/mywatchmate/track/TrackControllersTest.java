package com.mywatchmate.track;

import static com.mywatchmate.web.WebLayerTestConfig.asGuest;
import static com.mywatchmate.web.WebLayerTestConfig.asUser;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mywatchmate.auth.AuthPrincipal;
import com.mywatchmate.common.ApiException;
import com.mywatchmate.track.dto.AttendeeResponse;
import com.mywatchmate.track.dto.MyAttendanceResponse;
import com.mywatchmate.track.dto.NoteResponse;
import com.mywatchmate.track.dto.RoomAttendanceResponse;
import com.mywatchmate.track.dto.SessionAttendanceResponse;
import com.mywatchmate.track.dto.StreakResponse;
import com.mywatchmate.watch.WatchSession;
import com.mywatchmate.watch.dto.PersonRef;
import com.mywatchmate.web.WebLayerTestConfig;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest({AttendanceController.class, StreakController.class, NoteController.class})
@Import(WebLayerTestConfig.class)
class TrackControllersTest {

    private static final UUID USER = UUID.randomUUID();
    private static final UUID SPACE = UUID.randomUUID();
    private static final UUID SESSION = UUID.randomUUID();
    private static final Instant T0 = Instant.parse("2026-11-02T05:00:00Z");

    @Autowired MockMvc mvc;
    @MockitoBean AttendanceService attendance;
    @MockitoBean StreakService streaks;
    @MockitoBean NoteService notes;

    private static NoteResponse note(String body) {
        return new NoteResponse(UUID.randomUUID(), SPACE, null, new PersonRef(UUID.randomUUID(), "Tunde"), body,
            Note.Visibility.SHARED, 750, T0, T0);
    }

    @Test
    void myStreak() throws Exception {
        when(streaks.mine(USER)).thenReturn(new StreakResponse(5, 12, LocalDate.of(2026, 11, 6), 4, "Africa/Lagos"));

        mvc.perform(get("/api/v1/me/streak").with(asUser(USER)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.current").value(5))
            .andExpect(jsonPath("$.longest").value(12))
            .andExpect(jsonPath("$.lastAttendedOn").value("2026-11-06"))
            .andExpect(jsonPath("$.countedThisWeek").value(4))
            .andExpect(jsonPath("$.timezone").value("Africa/Lagos"));
    }

    @Test
    void guestsHaveNoStreakOrHistory() throws Exception {
        mvc.perform(get("/api/v1/me/streak").with(asGuest(UUID.randomUUID(), SPACE)))
            .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/me/attendance").with(asGuest(UUID.randomUUID(), SPACE)))
            .andExpect(status().isForbidden());
        verifyNoInteractions(streaks, attendance);
    }

    @Test
    void myHistoryPassesTheRange() throws Exception {
        when(attendance.mine(eq(USER), eq(T0), isNull())).thenReturn(List.of(new MyAttendanceResponse(SESSION,
            "Morning Devotion", SPACE, "6am Prayer Room", T0, T0.plusSeconds(1800), 1260, true)));

        mvc.perform(get("/api/v1/me/attendance").param("from", T0.toString()).with(asUser(USER)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].spaceTitle").value("6am Prayer Room"))
            .andExpect(jsonPath("$[0].counted").value(true));
    }

    @Test
    void sessionAttendanceForGuestsToo() throws Exception {
        UUID bisi = UUID.randomUUID();
        when(attendance.forSession(AuthPrincipal.guest(bisi, SPACE), SPACE, SESSION)).thenReturn(
            new SessionAttendanceResponse(SESSION, WatchSession.Status.ENDED, T0, T0.plusSeconds(1800), 900, 1, 1,
                List.of(new AttendeeResponse(bisi, "Bisi", true, T0, T0.plusSeconds(1800), 1500, true))));

        mvc.perform(get("/api/v1/spaces/" + SPACE + "/sessions/" + SESSION + "/attendance").with(asGuest(bisi, SPACE)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.requiredSeconds").value(900))
            .andExpect(jsonPath("$.people[0].displayName").value("Bisi"))
            .andExpect(jsonPath("$.people[0].counted").value(true));
    }

    @Test
    void roomSummaryRefusalsKeepTheirCodes() throws Exception {
        when(attendance.forRoom(any(), eq(SPACE), any(), any()))
            .thenThrow(ApiException.forbidden("FORBIDDEN_ROLE", "Only the room owner or admins can do this."))
            .thenReturn(new RoomAttendanceResponse(T0, T0.plusSeconds(60), List.of(), List.of()));

        mvc.perform(get("/api/v1/spaces/" + SPACE + "/attendance").with(asUser(USER)))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("FORBIDDEN_ROLE"));
        mvc.perform(get("/api/v1/spaces/" + SPACE + "/attendance").with(asUser(USER))
                .param("from", T0.toString()).param("to", T0.plusSeconds(60).toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.sessions").isEmpty());
        verify(attendance).forRoom(AuthPrincipal.user(USER), SPACE, T0, T0.plusSeconds(60));
    }

    @Test
    void badDatesAreRejected() throws Exception {
        mvc.perform(get("/api/v1/spaces/" + SPACE + "/attendance").param("from", "yesterday").with(asUser(USER)))
            .andExpect(status().isBadRequest());
        verifyNoInteractions(attendance);
    }

    @Test
    void notesListWithFilters() throws Exception {
        when(notes.list(AuthPrincipal.user(USER), SPACE, SESSION, true)).thenReturn(List.of(note("Verse 4")));

        mvc.perform(get("/api/v1/spaces/" + SPACE + "/notes").with(asUser(USER))
                .param("sessionId", SESSION.toString()).param("mine", "true"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].body").value("Verse 4"))
            .andExpect(jsonPath("$[0].positionSeconds").value(750))
            .andExpect(jsonPath("$[0].author.displayName").value("Tunde"));
    }

    @Test
    void writingANote() throws Exception {
        when(notes.create(eq(AuthPrincipal.user(USER)), eq(SPACE), any())).thenReturn(note("Amen"));

        mvc.perform(post("/api/v1/spaces/" + SPACE + "/notes").with(asUser(USER)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"body\":\"Amen\",\"visibility\":\"SHARED\",\"positionSeconds\":750}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.visibility").value("SHARED"));
        verify(notes).create(eq(AuthPrincipal.user(USER)), eq(SPACE),
            argThat(r -> r.visibility() == Note.Visibility.SHARED && r.positionSeconds() == 750));
    }

    @Test
    void emptyOrBadNotesAreRejectedBeforeTheService() throws Exception {
        mvc.perform(post("/api/v1/spaces/" + SPACE + "/notes").with(asUser(USER)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"body\":\"   \"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errors.body").exists());
        mvc.perform(post("/api/v1/spaces/" + SPACE + "/notes").with(asUser(USER)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"body\":\"x\",\"positionSeconds\":-1}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errors.positionSeconds").exists());
        mvc.perform(post("/api/v1/spaces/" + SPACE + "/notes").with(asUser(USER)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"body\":\"" + "x".repeat(5001) + "\"}"))
            .andExpect(status().isBadRequest());
        mvc.perform(patch("/api/v1/spaces/" + SPACE + "/notes/" + UUID.randomUUID()).with(asUser(USER))
                .contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"  \"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errors.body").exists());
        verifyNoInteractions(notes);
    }

    @Test
    void editingAndDeleting() throws Exception {
        UUID noteId = UUID.randomUUID();
        when(notes.update(eq(AuthPrincipal.user(USER)), eq(SPACE), eq(noteId), any())).thenReturn(note("Verse 5"));

        mvc.perform(patch("/api/v1/spaces/" + SPACE + "/notes/" + noteId).with(asUser(USER))
                .contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"Verse 5\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.body").value("Verse 5"));
        mvc.perform(delete("/api/v1/spaces/" + SPACE + "/notes/" + noteId).with(asUser(USER)))
            .andExpect(status().isNoContent());
        verify(notes).delete(AuthPrincipal.user(USER), SPACE, noteId);
    }

    @Test
    void someoneElsesNoteIsNotYours() throws Exception {
        UUID noteId = UUID.randomUUID();
        when(notes.update(any(), eq(SPACE), eq(noteId), any()))
            .thenThrow(ApiException.forbidden("NOT_YOUR_NOTE", "Only the person who wrote this note can edit it."));

        mvc.perform(patch("/api/v1/spaces/" + SPACE + "/notes/" + noteId).with(asUser(USER))
                .contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"x\"}"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("NOT_YOUR_NOTE"));
    }

    @Test
    void everythingNeedsSignIn() throws Exception {
        mvc.perform(get("/api/v1/spaces/" + SPACE + "/notes")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/me/streak")).andExpect(status().isUnauthorized());
    }
}
