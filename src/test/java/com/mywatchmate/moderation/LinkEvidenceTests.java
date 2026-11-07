package com.mywatchmate.moderation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.mywatchmate.moderation.dto.LinkDetail;
import com.mywatchmate.watch.content.ResolvedLink;
import com.mywatchmate.watch.content.VideoMetadata;
import com.mywatchmate.watch.content.VideoMetadataClient;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

/** The review screen's evidence queries against the real database (rolled back). */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class LinkEvidenceTests {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired LinkEvidence evidence;
    @MockitoBean VideoMetadataClient metadata;

    @Test
    void reportsRoomsAndGiftsAreGatheredWithoutNamingReporters() throws Exception {
        when(metadata.fetch(any(ResolvedLink.class))).thenReturn(new VideoMetadata("Sunday Service", null, null));
        String link = "https://pay.payaza.africa/give/evidence-" + UUID.randomUUID();
        String ada = register("Ada");
        String first = createRoom(ada, "Morning Service");
        String second = createRoom(ada, "Evening Service");
        startSession(ada, first);
        startSession(ada, second);
        String promptHere = read(showPrompt(ada, first, link), "$.id");
        String promptThere = read(showPrompt(ada, second, link), "$.id");
        showPrompt(ada, first, link);
        // The evening room showed it an hour earlier (so the order doesn't depend on the clock's resolution)
        jdbc.sql("UPDATE cue_detections SET detected_at = detected_at - interval '1 hour' WHERE id = :id")
            .param("id", UUID.fromString(promptThere)).update();
        UUID linkId = jdbc.sql("SELECT id FROM payment_links WHERE normalized_url = :url").param("url", link)
            .query(UUID.class).single();

        // Reports: one reviewed, one whose prompt was later deleted (no room)
        UUID review = UUID.randomUUID();
        UUID adaId = jdbc.sql("SELECT owner_id FROM spaces WHERE id = :id").param("id", UUID.fromString(first)).query(UUID.class).single();
        jdbc.sql("""
                INSERT INTO link_reviews (id, payment_link_id, reviewer_id, reviewer_name, decision, from_status, to_status,
                                          note, reports_reviewed, decided_at)
                VALUES (:id, :link, :ada, 'Ada', 'CLEAR', 'HELD', 'ALLOWED', 'fine', 1, now())
                """).param("id", review).param("link", linkId).param("ada", adaId).update();
        Instant t = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        report(linkId, "user:" + UUID.randomUUID(), UUID.fromString(promptHere), "Not ours", t.minusSeconds(30), review);
        report(linkId, "guest:" + UUID.randomUUID(), UUID.fromString(promptHere), null, t.minusSeconds(20), null);
        report(linkId, "user:" + UUID.randomUUID(), null, "Seen it elsewhere", t.minusSeconds(10), null);

        List<LinkDetail.Report> reports = evidence.reports(linkId);
        assertThat(reports).extracting(LinkDetail.Report::reason).containsExactly("Seen it elsewhere", null, "Not ours");
        assertThat(reports).extracting(LinkDetail.Report::reporterKind).containsExactly("USER", "GUEST", "USER");
        assertThat(reports).extracting(LinkDetail.Report::roomTitle).containsExactly(null, "Morning Service", "Morning Service");
        assertThat(reports).extracting(LinkDetail.Report::reviewed).containsExactly(false, false, true);
        assertThat(reports.getFirst().reportedAt()).isEqualTo(t.minusSeconds(10));

        // Rooms: most recently shown first, counted per room, by whom
        List<LinkDetail.Room> rooms = evidence.rooms(linkId);
        assertThat(rooms).extracting(LinkDetail.Room::title).containsExactly("Morning Service", "Evening Service");
        assertThat(rooms.getFirst().timesShown()).isEqualTo(2);
        assertThat(rooms.getFirst().byHost()).isTrue();
        assertThat(rooms.getFirst().byAi()).isFalse();
        assertThat(rooms.getFirst().spaceId()).isEqualTo(UUID.fromString(first));

        // Gifts: one count per status
        UUID sessionId = jdbc.sql("SELECT session_id FROM cue_detections WHERE id = :id").param("id", UUID.fromString(promptHere))
            .query(UUID.class).single();
        UUID participant = jdbc.sql("SELECT id FROM participants WHERE space_id = :s").param("s", UUID.fromString(first))
            .query(UUID.class).single();
        for (String s : List.of("OPENED", "OPENED", "COMPLETED", "FAILED", "CANCELLED", "UNKNOWN", "UNKNOWN", "UNKNOWN")) {
            jdbc.sql("INSERT INTO gifts (session_id, participant_id, payment_link_id, status) VALUES (:s, :p, :l, :st)")
                .param("s", sessionId).param("p", participant).param("l", linkId).param("st", s).update();
        }
        assertThat(evidence.gifts(linkId)).isEqualTo(new LinkDetail.Gifts(2, 1, 1, 1, 3));
    }

    @Test
    void aLinkWithNothingAgainstItHasEmptyEvidence() {
        UUID linkId = jdbc.sql("INSERT INTO payment_links (normalized_url, provider) VALUES (:url, 'PAYAZA') RETURNING id")
            .param("url", "https://pay.payaza.africa/give/quiet-" + UUID.randomUUID()).query(UUID.class).single();

        assertThat(evidence.reports(linkId)).isEmpty();
        assertThat(evidence.rooms(linkId)).isEmpty();
        assertThat(evidence.gifts(linkId)).isEqualTo(new LinkDetail.Gifts(0, 0, 0, 0, 0));
    }

    // ---- helpers -------------------------------------------------------------

    private void report(UUID link, String reporter, UUID detection, String reason, Instant at, UUID review) {
        jdbc.sql("""
                INSERT INTO payment_link_reports (payment_link_id, reporter_key, detection_id, reason, reported_at, review_id)
                VALUES (:link, :reporter, :detection, :reason, :at, :review)
                """)
            .param("link", link).param("reporter", reporter)
            .param("detection", detection, java.sql.Types.OTHER)
            .param("reason", reason, java.sql.Types.VARCHAR)
            .param("at", java.sql.Timestamp.from(at))
            .param("review", review, java.sql.Types.OTHER)
            .update();
    }

    private org.springframework.test.web.servlet.ResultActions showPrompt(String token, String spaceId, String url) throws Exception {
        return mvc.perform(json(post("/api/v1/spaces/" + spaceId + "/give/prompts"), token, "{\"url\":\"" + url + "\"}"))
            .andExpect(status().isCreated());
    }

    private void startSession(String token, String spaceId) throws Exception {
        mvc.perform(json(post("/api/v1/spaces/" + spaceId + "/sessions/watch-now"), token, "{}")).andExpect(status().isCreated());
    }

    private String register(String name) throws Exception {
        return read(mvc.perform(json(post("/api/v1/auth/register"), null, """
                {"email":"%s-%s@example.com","password":"correct-horse-battery","displayName":"%s"}"""
                .formatted(name.toLowerCase(), UUID.randomUUID(), name))).andExpect(status().isCreated()), "$.accessToken");
    }

    private String createRoom(String token, String title) throws Exception {
        return read(mvc.perform(json(post("/api/v1/spaces"), token, """
                {"title":"%s","audienceType":"DIASPORA","videoUrl":"https://youtu.be/dQw4w9WgXcQ"}""".formatted(title)))
            .andExpect(status().isCreated()), "$.id");
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String token, String body) {
        MockHttpServletRequestBuilder withBody = request.contentType(MediaType.APPLICATION_JSON).content(body);
        return token == null ? withBody : withBody.header("Authorization", "Bearer " + token);
    }

    private static String read(org.springframework.test.web.servlet.ResultActions result, String path) throws Exception {
        return JsonPath.read(result.andReturn().getResponse().getContentAsString(), path).toString();
    }
}
