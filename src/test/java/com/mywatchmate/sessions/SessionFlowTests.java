package com.mywatchmate.sessions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import com.mywatchmate.notify.EmailMessage;
import com.mywatchmate.notify.EmailSender;
import com.mywatchmate.notify.ReminderDispatchJob;
import com.mywatchmate.track.Reminder;
import com.mywatchmate.track.ReminderRepository;
import com.mywatchmate.watch.WatchSession;
import com.mywatchmate.watch.WatchSessionRepository;
import com.mywatchmate.watch.content.ResolvedLink;
import com.mywatchmate.watch.content.VideoMetadata;
import com.mywatchmate.watch.content.VideoMetadataClient;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

/**
 * Schedules, sessions and reminders end to end against the real database (rolled back).
 * The clock is controlled ("it's now Monday 04:50 UTC") and jobs are run by hand.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class SessionFlowTests {

    /** Sunday 1 Nov 2026, noon UTC. */
    private static final Instant SUNDAY_NOON = Instant.parse("2026-11-01T12:00:00Z");
    private static final Instant MONDAY_6AM_LAGOS = Instant.parse("2026-11-02T05:00:00Z");

    @Autowired MockMvc mvc;
    @Autowired SessionGeneratorJob generator;
    @Autowired SessionLifecycleJob lifecycle;
    @Autowired ReminderDispatchJob dispatch;
    @Autowired ReminderRepository reminders;
    @Autowired WatchSessionRepository sessions;
    @PersistenceContext EntityManager entityManager;
    @MockitoBean Clock clock;
    @MockitoBean EmailSender email;
    @MockitoBean VideoMetadataClient metadata;

    private final AtomicReference<Instant> now = new AtomicReference<>(SUNDAY_NOON);
    private final java.util.Map<String, String> emails = new java.util.HashMap<>();

    @BeforeEach
    void controlledWorld() {
        when(clock.instant()).thenAnswer(i -> now.get());
        when(metadata.fetch(any(ResolvedLink.class))).thenReturn(new VideoMetadata("Morning Prayer", null, null));
    }

    @Test
    void weekdayPrayerFromScheduleToRemindersToLiveToEnded() throws Exception {
        String ada = register("Ada", "Africa/Lagos");
        String tunde = register("Tunde", "Europe/London");
        String spaceId = createRoom(ada);
        join(tunde, spaceId, ada);

        // Weekdays at 06:00 Lagos time: two weeks of sittings appear at once
        mvc.perform(json(post("/api/v1/spaces/" + spaceId + "/schedules"), ada, """
                {"title":"Morning Devotion","days":["MONDAY","TUESDAY","WEDNESDAY","THURSDAY","FRIDAY"],
                 "time":"06:00","timezone":"Africa/Lagos","startsOn":"2026-11-02"}"""))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.nextSessionAt").value("2026-11-02T05:00:00Z"));

        // Tunde in London sees 05:00 his time; Ada in Lagos sees 06:00 hers
        MvcResult forTunde = mvc.perform(auth(get("/api/v1/spaces/" + spaceId + "/sessions"), tunde))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(10))
            .andExpect(jsonPath("$[0].scheduledAt").value("2026-11-02T05:00:00Z"))
            .andExpect(jsonPath("$[0].scheduledAtLocal").value("2026-11-02T05:00:00Z"))
            .andExpect(jsonPath("$[0].localTimezone").value("Europe/London"))
            .andExpect(jsonPath("$[0].title").value("Morning Devotion"))
            .andReturn();
        mvc.perform(auth(get("/api/v1/me/upcoming"), ada))
            .andExpect(jsonPath("$[0].scheduledAtLocal").value("2026-11-02T06:00:00+01:00"))
            .andExpect(jsonPath("$[0].spaceTitle").value("6am Prayer Room"));
        UUID firstSession = UUID.fromString(read(forTunde, "$[0].id"));

        // Both get a reminder 10 minutes before; Tunde moves his to 30 minutes
        assertThat(reminders.findBySessionId(firstSession)).extracting(Reminder::getSendAt)
            .containsOnly(MONDAY_6AM_LAGOS.minusSeconds(600));
        mvc.perform(json(put("/api/v1/spaces/" + spaceId + "/reminders"), tunde, "{\"enabled\":true,\"minutesBefore\":30}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.minutesBefore").value(30));
        assertThat(reminders.findBySessionId(firstSession)).extracting(Reminder::getSendAt)
            .containsExactlyInAnyOrder(MONDAY_6AM_LAGOS.minusSeconds(600), MONDAY_6AM_LAGOS.minusSeconds(1800));

        // 04:35 UTC Monday: only Tunde's is due, in his own time
        now.set(Instant.parse("2026-11-02T04:35:00Z"));
        dispatch.run();
        assertThat(emailsTo("Ada")).isEmpty();
        EmailMessage toTunde = only(emailsTo("Tunde"));
        assertThat(toTunde.subject()).isEqualTo("Starting in 25 minutes: 6am Prayer Room");
        assertThat(toTunde.text()).contains("When: Mon 2 Nov, 05:00 (Europe/London)", "\"Morning Devotion\"");

        // 04:51: Ada's; running again sends nothing more
        now.set(Instant.parse("2026-11-02T04:51:00Z"));
        clearInvocations(email);
        dispatch.run();
        assertThat(only(emailsTo("Ada")).text()).contains("When: Mon 2 Nov, 06:00 (Africa/Lagos)");
        assertThat(emailsTo("Tunde")).isEmpty();
        clearInvocations(email);
        dispatch.run();
        assertThat(emailsTo("Ada")).isEmpty();

        // 06:00 Lagos: the session starts by itself
        now.set(MONDAY_6AM_LAGOS.plusSeconds(10));
        assertThat(lifecycle.startDue()).isPositive();
        mvc.perform(auth(get("/api/v1/spaces/" + spaceId + "/sessions"), tunde))
            .andExpect(jsonPath("$[0].status").value("LIVE"))
            .andExpect(jsonPath("$[0].startedAt").value("2026-11-02T05:00:10Z"));

        // No known video length: it ends itself 3 hours later
        now.set(MONDAY_6AM_LAGOS.plusSeconds(3 * 3600 + 20));
        assertThat(lifecycle.endFinished()).isPositive();
        assertThat(sessions.findById(firstSession).orElseThrow().getStatus()).isEqualTo(WatchSession.Status.ENDED);

        // The hourly generator keeps the window filled: Mon 16 Nov now fits in
        assertThat(generator.run()).isGreaterThanOrEqualTo(1);
        mvc.perform(auth(get("/api/v1/spaces/" + spaceId + "/sessions"), ada))
            .andExpect(jsonPath("$[-1].scheduledAt").value("2026-11-16T05:00:00Z"));
    }

    @Test
    void changingThePlanReplacesUpcomingSittingsAndDeletingCancelsThem() throws Exception {
        String ada = register("Ada", "Africa/Lagos");
        String spaceId = createRoom(ada);
        String scheduleId = read(mvc.perform(json(post("/api/v1/spaces/" + spaceId + "/schedules"), ada, """
                {"days":["MONDAY","TUESDAY","WEDNESDAY","THURSDAY","FRIDAY"],"time":"06:00",
                 "timezone":"Africa/Lagos","startsOn":"2026-11-02"}"""))
            .andExpect(status().isCreated()).andReturn(), "$.id");

        mvc.perform(json(put("/api/v1/spaces/" + spaceId + "/schedules/" + scheduleId), ada, """
                {"title":"Friday film","days":["FRIDAY"],"time":"19:00","timezone":"Europe/London","startsOn":"2026-11-02"}"""))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.days.length()").value(1))
            .andExpect(jsonPath("$.nextSessionAt").value("2026-11-06T19:00:00Z"));
        mvc.perform(auth(get("/api/v1/spaces/" + spaceId + "/sessions"), ada))
            .andExpect(jsonPath("$.length()").value(2))
            .andExpect(jsonPath("$[*].title").value(org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is("Friday film"))));

        mvc.perform(auth(delete("/api/v1/spaces/" + spaceId + "/schedules/" + scheduleId), ada)).andExpect(status().isNoContent());
        mvc.perform(auth(get("/api/v1/spaces/" + spaceId + "/sessions"), ada)).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(auth(get("/api/v1/spaces/" + spaceId + "/schedules"), ada)).andExpect(jsonPath("$.length()").value(0));
        // Read straight from the database: in a test, all requests share one transaction and its cache
        entityManager.flush();
        entityManager.clear();
        List<WatchSession> kept = sessions.findBetween(UUID.fromString(spaceId), Instant.EPOCH, Instant.parse("2027-01-01T00:00:00Z"));
        assertThat(kept).hasSize(2).allSatisfy(s -> assertThat(s.getStatus()).isEqualTo(WatchSession.Status.CANCELLED));
        assertThat(kept).allSatisfy(s -> assertThat(reminders.findBySessionId(s.getId()))
            .allSatisfy(r -> assertThat(r.getStatus()).isEqualTo(Reminder.Status.CANCELLED)));
    }

    @Test
    void watchNowOneOffsAndWhoMayDoWhat() throws Exception {
        String ada = register("Ada", "Africa/Lagos");
        String tunde = register("Tunde", "Europe/London");
        String spaceId = createRoom(ada);
        join(tunde, spaceId, ada);
        String videoId = read(mvc.perform(json(post("/api/v1/spaces/" + spaceId + "/content"), ada,
                "{\"url\":\"https://youtu.be/dQw4w9WgXcQ\",\"playNow\":false}"))
            .andExpect(status().isCreated()).andReturn(), "$.id");

        // Members can't start sessions unless the room lets them add videos
        mvc.perform(auth(post("/api/v1/spaces/" + spaceId + "/sessions/watch-now"), tunde)).andExpect(status().isForbidden());

        String live = read(mvc.perform(json(post("/api/v1/spaces/" + spaceId + "/sessions/watch-now"), ada,
                "{\"title\":\"Catch-up\",\"contentId\":\"%s\"}".formatted(videoId)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("LIVE"))
            .andExpect(jsonPath("$.content.id").value(videoId))
            .andReturn(), "$.id");
        mvc.perform(auth(get("/api/v1/spaces/" + spaceId), tunde)).andExpect(jsonPath("$.nowPlaying.id").value(videoId));
        mvc.perform(auth(post("/api/v1/spaces/" + spaceId + "/sessions/watch-now"), ada))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SESSION_ALREADY_LIVE"));

        // A one-off on Saturday evening shows up in Tunde's week, with a reminder
        String oneOff = read(mvc.perform(json(post("/api/v1/spaces/" + spaceId + "/sessions"), ada,
                "{\"scheduledAt\":\"2026-11-07T17:00:00Z\",\"title\":\"Film night\"}"))
            .andExpect(status().isCreated()).andReturn(), "$.id");
        mvc.perform(auth(get("/api/v1/me/upcoming"), tunde))
            .andExpect(jsonPath("$[*].title").value(org.hamcrest.Matchers.hasItems("Catch-up", "Film night")));
        assertThat(reminders.findBySessionId(UUID.fromString(oneOff))).hasSize(2);

        // It can't start early while something is live; end that first
        mvc.perform(auth(post("/api/v1/spaces/" + spaceId + "/sessions/" + oneOff + "/start"), ada))
            .andExpect(status().isConflict());
        mvc.perform(auth(post("/api/v1/spaces/" + spaceId + "/sessions/" + live + "/end"), tunde)).andExpect(status().isForbidden());
        mvc.perform(auth(post("/api/v1/spaces/" + spaceId + "/sessions/" + live + "/end"), ada))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ENDED"));
        mvc.perform(auth(post("/api/v1/spaces/" + spaceId + "/sessions/" + oneOff + "/start"), ada))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("LIVE"));
    }

    @Test
    void cancellingAndSwitchingOffStopReminderEmails() throws Exception {
        String ada = register("Ada", "Africa/Lagos");
        String tunde = register("Tunde", "Europe/London");
        String spaceId = createRoom(ada);
        join(tunde, spaceId, ada);
        String cancelled = read(mvc.perform(json(post("/api/v1/spaces/" + spaceId + "/sessions"), ada,
                "{\"scheduledAt\":\"2026-11-02T05:00:00Z\"}")).andReturn(), "$.id");
        mvc.perform(json(post("/api/v1/spaces/" + spaceId + "/sessions"), ada, "{\"scheduledAt\":\"2026-11-02T06:00:00Z\"}"))
            .andExpect(status().isCreated());

        mvc.perform(auth(post("/api/v1/spaces/" + spaceId + "/sessions/" + cancelled + "/cancel"), ada))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
        mvc.perform(json(patch("/api/v1/me"), tunde, "{\"emailReminders\":false}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.emailReminders").value(false));

        // Both sessions' reminders are now due: only Ada's for the second session goes out
        now.set(Instant.parse("2026-11-02T05:55:00Z"));
        dispatch.run();
        assertThat(emailsTo("Tunde")).isEmpty();
        assertThat(only(emailsTo("Ada")).subject()).isEqualTo("Starting in 5 minutes: 6am Prayer Room");
    }

    @Test
    void guestsGetNoRemindersAndCantSeeReminderSettings() throws Exception {
        String ada = register("Ada", "Africa/Lagos");
        String spaceId = createRoom(ada);
        String invite = read(mvc.perform(auth(get("/api/v1/spaces/" + spaceId + "/invite"), ada)).andReturn(), "$.inviteToken");
        String bisi = read(mvc.perform(json(post("/api/v1/auth/guest"), null,
            "{\"inviteToken\":\"%s\",\"displayName\":\"Bisi\"}".formatted(invite))).andReturn(), "$.accessToken");
        String session = read(mvc.perform(json(post("/api/v1/spaces/" + spaceId + "/sessions"), ada,
            "{\"scheduledAt\":\"2026-11-02T05:00:00Z\"}")).andReturn(), "$.id");

        assertThat(reminders.findBySessionId(UUID.fromString(session))).hasSize(1);
        mvc.perform(auth(get("/api/v1/spaces/" + spaceId + "/reminders"), bisi)).andExpect(status().isForbidden());
        mvc.perform(auth(get("/api/v1/spaces/" + spaceId + "/sessions"), bisi))
            .andExpect(status().isOk()).andExpect(jsonPath("$[0].localTimezone").value("UTC"));
        verify(email, never()).send(any());
    }

    // ---- helpers -------------------------------------------------------------

    private String register(String name, String timezone) throws Exception {
        String address = name.toLowerCase() + "-" + UUID.randomUUID() + "@example.com";
        emails.put(name, address);
        return read(mvc.perform(json(post("/api/v1/auth/register"), null, """
                {"email":"%s","password":"correct-horse-battery","displayName":"%s","timezone":"%s"}"""
                .formatted(address, name, timezone)))
            .andExpect(status().isCreated()).andReturn(), "$.accessToken");
    }

    private String createRoom(String token) throws Exception {
        return read(mvc.perform(json(post("/api/v1/spaces"), token, "{\"title\":\"6am Prayer Room\",\"audienceType\":\"DIASPORA\"}"))
            .andExpect(status().isCreated()).andReturn(), "$.id");
    }

    private void join(String token, String spaceId, String ownerToken) throws Exception {
        String invite = read(mvc.perform(auth(get("/api/v1/spaces/" + spaceId + "/invite"), ownerToken)).andReturn(), "$.inviteToken");
        mvc.perform(auth(post("/api/v1/invites/" + invite + "/join"), token)).andExpect(status().isOk());
    }

    /**
     * Emails sent to one of this test's people since the last clearInvocations. Jobs run over the whole
     * database (on a dev machine that includes real data), so tests look only at their own recipients.
     */
    private List<EmailMessage> emailsTo(String name) {
        String address = emails.get(name);
        return org.mockito.Mockito.mockingDetails(email).getInvocations().stream()
            .filter(i -> i.getMethod().getName().equals("send"))
            .map(i -> (EmailMessage) i.getArgument(0))
            .filter(m -> m.to().equals(address))
            .toList();
    }

    private static EmailMessage only(List<EmailMessage> messages) {
        assertThat(messages).hasSize(1);
        return messages.get(0);
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
