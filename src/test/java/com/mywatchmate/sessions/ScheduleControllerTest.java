package com.mywatchmate.sessions;

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
import com.mywatchmate.sessions.dto.ScheduleResponse;
import com.mywatchmate.web.WebLayerTestConfig;
import java.time.DayOfWeek;
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

@WebMvcTest(ScheduleController.class)
@Import(WebLayerTestConfig.class)
class ScheduleControllerTest {

    private static final UUID USER = UUID.randomUUID();
    private static final UUID SPACE = UUID.randomUUID();
    private static final String BASE = "/api/v1/spaces/" + SPACE + "/schedules";
    private static final String WEEKDAYS_6AM = """
        {"title":"Morning Devotion","days":["MONDAY","TUESDAY","WEDNESDAY","THURSDAY","FRIDAY"],
         "time":"06:00","timezone":"Africa/Lagos","startsOn":"2026-11-02"}""";

    @Autowired MockMvc mvc;
    @MockitoBean ScheduleService schedules;

    @Test
    void createIs201WithTheParsedPlan() throws Exception {
        when(schedules.create(eq(AuthPrincipal.user(USER)), eq(SPACE), any())).thenReturn(response());

        mvc.perform(post(BASE).with(asUser(USER)).contentType(MediaType.APPLICATION_JSON).content(WEEKDAYS_6AM))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.time").value("06:00"))
            .andExpect(jsonPath("$.days[0]").value("MONDAY"))
            .andExpect(jsonPath("$.nextSessionAt").value("2026-11-02T05:00:00Z"));
        verify(schedules).create(eq(AuthPrincipal.user(USER)), eq(SPACE), argThat(r -> r.days().size() == 5
            && r.time().equals("06:00") && r.startsOn().equals(LocalDate.of(2026, 11, 2)) && r.endsOn() == null));
    }

    @Test
    void timeMustBeHhMmAndDaysAndStartAreRequired() throws Exception {
        mvc.perform(post(BASE).with(asUser(USER)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"days\":[],\"time\":\"6am\",\"timezone\":\"Africa/Lagos\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errors.days").exists())
            .andExpect(jsonPath("$.errors.time").value("must be HH:mm, e.g. 06:00"))
            .andExpect(jsonPath("$.errors.startsOn").exists());
        mvc.perform(post(BASE).with(asUser(USER)).contentType(MediaType.APPLICATION_JSON)
                .content(WEEKDAYS_6AM.replace("\"06:00\"", "\"24:00\"")))
            .andExpect(status().isBadRequest());
        mvc.perform(post(BASE).with(asUser(USER)).contentType(MediaType.APPLICATION_JSON)
                .content(WEEKDAYS_6AM.replace("MONDAY", "FUNDAY")))
            .andExpect(status().isBadRequest());
        verifyNoInteractions(schedules);
    }

    @Test
    void guestsCanListSchedules() throws Exception {
        UUID guest = UUID.randomUUID();
        when(schedules.list(AuthPrincipal.guest(guest, SPACE), SPACE)).thenReturn(List.of(response()));

        mvc.perform(get(BASE).with(asGuest(guest, SPACE)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].timezone").value("Africa/Lagos"));
    }

    @Test
    void replaceAndDelete() throws Exception {
        UUID scheduleId = UUID.randomUUID();
        when(schedules.replace(eq(AuthPrincipal.user(USER)), eq(SPACE), eq(scheduleId), any())).thenReturn(response());

        mvc.perform(put(BASE + "/" + scheduleId).with(asUser(USER)).contentType(MediaType.APPLICATION_JSON).content(WEEKDAYS_6AM))
            .andExpect(status().isOk());
        mvc.perform(delete(BASE + "/" + scheduleId).with(asUser(USER))).andExpect(status().isNoContent());

        verify(schedules).delete(AuthPrincipal.user(USER), SPACE, scheduleId);
    }

    @Test
    void signInRequired() throws Exception {
        mvc.perform(get(BASE)).andExpect(status().isUnauthorized());
        verifyNoInteractions(schedules);
    }

    private static ScheduleResponse response() {
        return new ScheduleResponse(UUID.randomUUID(), SPACE, "Morning Devotion",
            List.of(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY),
            "06:00", "Africa/Lagos", LocalDate.of(2026, 11, 2), null, true, null, Instant.parse("2026-11-02T05:00:00Z"));
    }
}
