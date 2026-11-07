package com.mywatchmate.moderation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import jakarta.persistence.EntityManager;
import com.mywatchmate.watch.content.ResolvedLink;
import com.mywatchmate.watch.content.VideoMetadata;
import com.mywatchmate.watch.content.VideoMetadataClient;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

/** Reported links through review, over HTTP against the real database (rolled back). */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ModerationFlowTests {

    private static final String ADMIN = "/api/v1/admin/links";

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired EntityManager entityManager;
    @MockitoBean Clock clock;
    @MockitoBean VideoMetadataClient metadata;

    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.now().truncatedTo(ChronoUnit.SECONDS));

    @BeforeEach
    void controlledWorld() {
        when(clock.instant()).thenAnswer(i -> now.get());
        when(metadata.fetch(any(ResolvedLink.class))).thenReturn(new VideoMetadata("Sunday Service", null, null));
    }

    @Test
    void aReportedLinkIsHeldUntilAReviewerClearsOrConfirmsIt() throws Exception {
        String link = "https://pay.payaza.africa/give/review-" + UUID.randomUUID();
        String rita = staff("Rita", "ADMIN");
        String ada = register("Ada");
        String spaceId = createRoom(ada);
        String invite = inviteOf(ada, spaceId);
        String tunde = register("Tunde");
        String kemi = register("Kemi");
        String femi = register("Femi");
        join(tunde, invite);
        join(kemi, invite);
        join(femi, invite);
        String bisi = guestToken(invite, "Bisi");
        startSession(ada, spaceId);
        String promptId = read(showPrompt(ada, spaceId, link).andExpect(status().isCreated()).andReturn(), "$.id");

        // Staff only: checked in the database, guests stopped before that
        mvc.perform(auth(get("/api/v1/me"), rita)).andExpect(jsonPath("$.staffRole").value("ADMIN"));
        mvc.perform(auth(get("/api/v1/me"), ada)).andExpect(jsonPath("$.staffRole").doesNotExist());
        mvc.perform(auth(get(ADMIN), ada)).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("STAFF_ONLY"));
        mvc.perform(auth(get(ADMIN), bisi)).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(get(ADMIN)).andExpect(status().isUnauthorized());

        // Three reports hold it: down everywhere, can't be shown again
        report(tunde, spaceId, promptId, "Not our church");
        report(bisi, spaceId, promptId, null);
        report(kemi, spaceId, promptId, "Wrong account name");
        mvc.perform(auth(get("/api/v1/spaces/" + spaceId + "/give/prompts/current"), ada)).andExpect(status().isNoContent());
        showPrompt(ada, spaceId, link).andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("LINK_BLOCKED"))
            .andExpect(jsonPath("$.detail").value(containsString("reviewing")));

        // In the queue, with its evidence (never who reported)
        java.util.List<String> ids = JsonPath.read(mvc.perform(auth(get(ADMIN), rita)).andExpect(status().isOk())
            .andExpect(jsonPath("$.links[?(@.url == '" + link + "')].status").value(hasItem("HELD")))
            .andReturn().getResponse().getContentAsString(), "$.links[?(@.url == '" + link + "')].id");
        String linkId = ids.getFirst();
        String linkUrl = ADMIN + "/" + linkId;
        mvc.perform(auth(get(linkUrl), rita))
            .andExpect(jsonPath("$.link.openReports").value(3))
            .andExpect(jsonPath("$.statusReason").value(containsString("Reported")))
            .andExpect(jsonPath("$.reports", hasSize(3)))
            .andExpect(jsonPath("$.reports[*].reporterKind").value(hasItem("GUEST")))
            .andExpect(jsonPath("$.reports[*].reason").value(hasItem("Wrong account name")))
            .andExpect(jsonPath("$.reports[0].roomTitle").value("Sunday Service"))
            .andExpect(jsonPath("$.reports[0].reporterKey").doesNotExist())
            .andExpect(jsonPath("$.rooms[0].title").value("Sunday Service"))
            .andExpect(jsonPath("$.rooms[0].byHost").value(true))
            .andExpect(jsonPath("$.rooms[0].byAi").value(false))
            .andExpect(jsonPath("$.gifts.opened").value(0))
            .andExpect(jsonPath("$.history", hasSize(0)));

        // A decision only applies to the status the reviewer saw
        decide(rita, linkId, "CLEAR", "BLOCKED").andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("LINK_STATUS_CHANGED"));
        decide(rita, linkId, "CLEAR", "HELD").andExpect(status().isOk())
            .andExpect(jsonPath("$.link.status").value("ALLOWED"))
            .andExpect(jsonPath("$.link.openReports").value(0))
            .andExpect(jsonPath("$.statusReason").doesNotExist())
            .andExpect(jsonPath("$.reports[*].reviewed").value(everyItem(org.hamcrest.Matchers.is(true))))
            .andExpect(jsonPath("$.history[0].decision").value("CLEAR"))
            .andExpect(jsonPath("$.history[0].reviewerName").value("Rita"))
            .andExpect(jsonPath("$.history[0].reportsReviewed").value(3))
            .andExpect(jsonPath("$.history[0].note").value("Checked with the church"));
        decide(rita, linkId, "CLEAR", "ALLOWED").andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("DECISION_NOT_ALLOWED"));

        // Cleared: it can be shown again, and old reports don't count towards holding it again
        String again = read(showPrompt(ada, spaceId, link).andExpect(status().isCreated()).andReturn(), "$.id");
        report(tunde, spaceId, again, "Still not ours");
        report(femi, spaceId, again, null);
        mvc.perform(auth(get(linkUrl), rita))
            .andExpect(jsonPath("$.link.status").value("ALLOWED"))
            .andExpect(jsonPath("$.link.openReports").value(1));

        // Confirmed: blocked for good, and its prompt comes down at once
        decide(rita, linkId, "CONFIRM_SCAM", "ALLOWED").andExpect(status().isOk())
            .andExpect(jsonPath("$.link.status").value("BLOCKED"))
            .andExpect(jsonPath("$.history", hasSize(2)));
        mvc.perform(auth(get("/api/v1/spaces/" + spaceId + "/give/prompts/current"), ada)).andExpect(status().isNoContent());
        mvc.perform(auth(get(ADMIN).param("status", "BLOCKED"), rita))
            .andExpect(jsonPath("$.links[?(@.id == '" + linkId + "')].status").value(hasItem("BLOCKED")));
        mvc.perform(auth(get(ADMIN).param("status", "ALLOWED"), rita)).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_STATUS"));

        // The trail can't be rewritten, even straight in the database
        assertThatThrownBy(() -> jdbc.sql("UPDATE link_reviews SET note = 'nothing to see' WHERE payment_link_id = :id")
            .param("id", UUID.fromString(linkId)).update())
            .isInstanceOf(DataAccessException.class)
            .hasMessageContaining("append-only");
    }

    @Test
    void staffCanBlockALinkBeforeAnyoneReportsIt() throws Exception {
        String link = "https://pay.payaza.africa/give/fake-" + UUID.randomUUID();
        String max = staff("Max", "MODERATOR");

        String body = "{\"url\":\"%s?utm_source=wa\",\"note\":\"Impersonating St Mark's\"}".formatted(link);
        mvc.perform(json(post(ADMIN + "/block"), max, body)).andExpect(status().isOk())
            .andExpect(jsonPath("$.link.url").value(link))
            .andExpect(jsonPath("$.link.status").value("BLOCKED"))
            .andExpect(jsonPath("$.history[0].decision").value("CONFIRM_SCAM"))
            .andExpect(jsonPath("$.history[0].fromStatus").value("ALLOWED"));
        mvc.perform(json(post(ADMIN + "/block"), max, body)).andExpect(status().isOk())
            .andExpect(jsonPath("$.history", hasSize(1)));
        mvc.perform(json(post(ADMIN + "/block"), max, "{\"url\":\"https://paystack.com/pay/x\",\"note\":\"n\"}"))
            .andExpect(status().isUnprocessableContent())
            .andExpect(jsonPath("$.code").value("UNSUPPORTED_PAYMENT_LINK"));

        String ada = register("Ada");
        String spaceId = createRoom(ada);
        startSession(ada, spaceId);
        showPrompt(ada, spaceId, link).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("LINK_BLOCKED"));
    }

    @Test
    void removingTheStaffRoleTakesEffectAtOnce() throws Exception {
        String email = "gone-" + UUID.randomUUID() + "@example.com";
        String token = register("Gone", email);
        grant(email, "MODERATOR");
        mvc.perform(auth(get(ADMIN), token)).andExpect(status().isOk());

        grant(email, null);

        mvc.perform(auth(get(ADMIN), token)).andExpect(status().isForbidden());
    }

    // ---- helpers -------------------------------------------------------------

    private String staff(String name, String role) throws Exception {
        String email = name.toLowerCase() + "-" + UUID.randomUUID() + "@example.com";
        String token = register(name, email);
        grant(email, role);
        return token;
    }

    /** As deploy/grant-staff.sh does: straight in the database (then forget what this test transaction cached). */
    private void grant(String email, String role) {
        entityManager.flush();
        assertThat(jdbc.sql("UPDATE users SET staff_role = :role WHERE email = :email")
            .param("role", role, java.sql.Types.VARCHAR).param("email", email).update()).isEqualTo(1);
        entityManager.clear();
    }

    private ResultActions decide(String token, String linkId, String decision, String expected) throws Exception {
        return mvc.perform(json(post(ADMIN + "/" + linkId + "/decision"), token, """
            {"decision":"%s","note":"  Checked with the church ","expectedStatus":"%s"}""".formatted(decision, expected)));
    }

    private void report(String token, String spaceId, String promptId, String reason) throws Exception {
        String body = reason == null ? "{}" : "{\"reason\":\"" + reason + "\"}";
        mvc.perform(json(post("/api/v1/spaces/" + spaceId + "/give/prompts/" + promptId + "/report"), token, body))
            .andExpect(status().isNoContent());
    }

    private ResultActions showPrompt(String token, String spaceId, String url) throws Exception {
        return mvc.perform(json(post("/api/v1/spaces/" + spaceId + "/give/prompts"), token, "{\"url\":\"" + url + "\"}"));
    }

    private void startSession(String token, String spaceId) throws Exception {
        mvc.perform(json(post("/api/v1/spaces/" + spaceId + "/sessions/watch-now"), token, "{}")).andExpect(status().isCreated());
    }

    private String register(String name) throws Exception {
        return register(name, name.toLowerCase() + "-" + UUID.randomUUID() + "@example.com");
    }

    private String register(String name, String email) throws Exception {
        return read(mvc.perform(json(post("/api/v1/auth/register"), null, """
                {"email":"%s","password":"correct-horse-battery","displayName":"%s"}""".formatted(email, name)))
            .andExpect(status().isCreated()).andReturn(), "$.accessToken");
    }

    private String createRoom(String token) throws Exception {
        return read(mvc.perform(json(post("/api/v1/spaces"), token, """
                {"title":"Sunday Service","audienceType":"DIASPORA","videoUrl":"https://youtu.be/dQw4w9WgXcQ"}"""))
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
