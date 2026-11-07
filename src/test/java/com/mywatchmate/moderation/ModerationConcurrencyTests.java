package com.mywatchmate.moderation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.mywatchmate.notify.EmailSender;
import com.mywatchmate.watch.content.ResolvedLink;
import com.mywatchmate.watch.content.VideoMetadata;
import com.mywatchmate.watch.content.VideoMetadataClient;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Things happening at the same moment, against the real database with real commits (not rolled back: the
 * point is how separate transactions interleave). Rows are left behind with random names.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ModerationConcurrencyTests {

    private static final int REPORTERS = 5;

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @MockitoBean VideoMetadataClient metadata;
    @MockitoBean EmailSender email;

    @Test
    void reportsArrivingTogetherAllCountAndHoldTheLinkOnce() throws Exception {
        when(metadata.fetch(any(ResolvedLink.class))).thenReturn(new VideoMetadata("Sunday Service", null, null));
        String staffEmail = "staff-" + UUID.randomUUID() + "@example.com";
        register("Rita", staffEmail);
        grant(staffEmail, "MODERATOR");
        String link = "https://pay.payaza.africa/give/rush-" + UUID.randomUUID();
        String ada = register("Ada", address());
        String spaceId = createRoom(ada);
        String invite = read(mvc.perform(auth(get("/api/v1/spaces/" + spaceId + "/invite"), ada))
            .andReturn().getResponse(), "$.inviteToken");
        List<String> members = new ArrayList<>();
        for (int i = 0; i < REPORTERS; i++) {
            String member = register("Member" + i, address());
            mvc.perform(auth(post("/api/v1/invites/" + invite + "/join"), member)).andExpect(status().isOk());
            members.add(member);
        }
        mvc.perform(json(post("/api/v1/spaces/" + spaceId + "/sessions/watch-now"), ada, "{}")).andExpect(status().isCreated());
        String promptId = read(mvc.perform(json(post("/api/v1/spaces/" + spaceId + "/give/prompts"), ada,
            "{\"url\":\"" + link + "\"}")).andExpect(status().isCreated()).andReturn().getResponse(), "$.id");

        List<Integer> statuses = allAtOnce(members.stream().<Callable<Integer>>map(member -> () ->
            mvc.perform(json(post("/api/v1/spaces/" + spaceId + "/give/prompts/" + promptId + "/report"), member, "{}"))
                .andReturn().getResponse().getStatus()).toList());

        assertThat(statuses).containsOnly(204);
        var row = jdbc.sql("SELECT report_count, status FROM payment_links WHERE normalized_url = :url").param("url", link)
            .query((rs, i) -> List.of(rs.getInt("report_count"), rs.getString("status"))).single();
        assertThat(row).containsExactly(REPORTERS, "HELD");
        assertThat(jdbc.sql("""
                SELECT count(*) FROM payment_link_reports r JOIN payment_links l ON l.id = r.payment_link_id
                WHERE l.normalized_url = :url""").param("url", link).query(Long.class).single()).isEqualTo(REPORTERS);
        // Held once, so staff hear about it once (emails go after commit, in the background)
        verify(email, after(2000).times(1)).send(argThat(m -> m != null && m.to().equals(staffEmail)
            && m.subject().equals("A payment link needs review") && m.text().contains(link)));
    }

    @Test
    void twoReviewersDecidingTogetherCantOverruleEachOther() throws Exception {
        String rita = staff("Rita");
        String max = staff("Max");
        UUID linkId = jdbc.sql("""
                INSERT INTO payment_links (normalized_url, provider, status, blocked_reason, report_count)
                VALUES (:url, 'PAYAZA', 'HELD', 'Reported by viewers; under review', 3) RETURNING id""")
            .param("url", "https://pay.payaza.africa/give/tug-" + UUID.randomUUID()).query(UUID.class).single();

        List<MockHttpServletResponse> responses = allAtOnce(List.of(
            () -> decide(rita, linkId, "CONFIRM_SCAM"),
            () -> decide(max, linkId, "CLEAR")));

        assertThat(responses).extracting(MockHttpServletResponse::getStatus).containsExactlyInAnyOrder(200, 409);
        MockHttpServletResponse refused = responses.stream().filter(r -> r.getStatus() == 409).findFirst().orElseThrow();
        assertThat(read(refused, "$.code")).isEqualTo("LINK_STATUS_CHANGED");
        MockHttpServletResponse won = responses.stream().filter(r -> r.getStatus() == 200).findFirst().orElseThrow();
        String finalStatus = jdbc.sql("SELECT status FROM payment_links WHERE id = :id").param("id", linkId)
            .query(String.class).single();
        assertThat(read(won, "$.link.status")).isEqualTo(finalStatus);
        assertThat(jdbc.sql("SELECT count(*) FROM link_reviews WHERE payment_link_id = :id").param("id", linkId)
            .query(Long.class).single()).isEqualTo(1);
    }

    // ---- helpers -------------------------------------------------------------

    /** Runs the calls on separate threads, released together. */
    private static <T> List<T> allAtOnce(List<Callable<T>> calls) throws Exception {
        CountDownLatch ready = new CountDownLatch(calls.size());
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(calls.size());
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> call : calls) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return call.call();
                }));
            }
            ready.await();
            go.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get());
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private MockHttpServletResponse decide(String token, UUID linkId, String decision) throws Exception {
        return mvc.perform(json(post("/api/v1/admin/links/" + linkId + "/decision"), token, """
            {"decision":"%s","note":"Checked","expectedStatus":"HELD"}""".formatted(decision))).andReturn().getResponse();
    }

    private String staff(String name) throws Exception {
        String address = address();
        String token = register(name, address);
        grant(address, "MODERATOR");
        return token;
    }

    private void grant(String address, String role) {
        assertThat(jdbc.sql("UPDATE users SET staff_role = :role WHERE email = :email")
            .param("role", role).param("email", address).update()).isEqualTo(1);
    }

    private static String address() {
        return "rush-" + UUID.randomUUID() + "@example.com";
    }

    private String register(String name, String address) throws Exception {
        return read(mvc.perform(json(post("/api/v1/auth/register"), null, """
                {"email":"%s","password":"correct-horse-battery","displayName":"%s"}""".formatted(address, name)))
            .andExpect(status().isCreated()).andReturn().getResponse(), "$.accessToken");
    }

    private String createRoom(String token) throws Exception {
        return read(mvc.perform(json(post("/api/v1/spaces"), token, """
                {"title":"Sunday Service","audienceType":"DIASPORA","videoUrl":"https://youtu.be/dQw4w9WgXcQ"}"""))
            .andExpect(status().isCreated()).andReturn().getResponse(), "$.id");
    }

    private static MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder request, String token) {
        return token == null ? request : request.header("Authorization", "Bearer " + token);
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String token, String body) {
        return auth(request, token).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static String read(MockHttpServletResponse response, String path) throws Exception {
        return JsonPath.read(response.getContentAsString(), path).toString();
    }
}
