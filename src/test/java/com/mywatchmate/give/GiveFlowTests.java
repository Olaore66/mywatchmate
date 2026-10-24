package com.mywatchmate.give;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.mywatchmate.watch.content.ResolvedLink;
import com.mywatchmate.watch.content.VideoMetadata;
import com.mywatchmate.watch.content.VideoMetadataClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

/** Giving over HTTP against the real database (rolled back), with a controlled clock and a test worker token. */
@SpringBootTest(properties = "mywatchmate.give.internal-token=test-worker-token")
@AutoConfigureMockMvc
@Transactional
class GiveFlowTests {

    private static final String LINK = "https://pay.payaza.africa/give/stmarks-" + UUID.randomUUID();

    @Autowired MockMvc mvc;
    @Autowired GiftExpiryJob expiry;
    @MockitoBean Clock clock;
    @MockitoBean VideoMetadataClient metadata;

    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.now().truncatedTo(ChronoUnit.SECONDS));

    @BeforeEach
    void controlledWorld() {
        when(clock.instant()).thenAnswer(i -> now.get());
        when(metadata.fetch(any(ResolvedLink.class))).thenReturn(new VideoMetadata("Sunday Service", null, null));
    }

    private void advance(Duration d) {
        now.set(now.get().plus(d));
    }

    @Test
    void aHostPutsUpTheGivingLinkAndPeopleGive() throws Exception {
        String ada = register("Ada");
        String tunde = register("Tunde");
        String spaceId = createRoom(ada);
        String invite = inviteOf(ada, spaceId);
        join(tunde, invite);
        String bisi = guestToken(invite, "Bisi");

        // Only during a live session, only hosts, only Payaza
        showPrompt(ada, spaceId, LINK).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("NO_LIVE_SESSION"));
        startSession(ada, spaceId);
        showPrompt(tunde, spaceId, LINK).andExpect(status().isForbidden());
        showPrompt(ada, spaceId, "https://paystack.com/pay/x").andExpect(status().isUnprocessableContent());
        String promptId = read(showPrompt(ada, spaceId, LINK + "?utm_source=yt")
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.url").value(LINK))
            .andExpect(jsonPath("$.source").value("HOST"))
            .andReturn(), "$.id");

        // Bisi arrives late and sees it; she gives anonymously, Tunde by name
        mvc.perform(auth(get("/api/v1/spaces/" + spaceId + "/give/prompts/current"), bisi))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(promptId));
        String bisiGift = openGift(bisi, spaceId, promptId, true);
        String tundeGift = openGift(tunde, spaceId, promptId, false);
        String forgotten = openGift(tunde, spaceId, promptId, false);

        result(bisi, bisiGift, "{\"status\":\"COMPLETED\",\"amountMinor\":500000,\"currency\":\"NGN\",\"providerTxnRef\":\"PZ-" + UUID.randomUUID() + "\"}")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("COMPLETED"))
            .andExpect(jsonPath("$.anonymous").value(true));
        result(tunde, tundeGift, "{\"status\":\"COMPLETED\",\"amountMinor\":250000,\"currency\":\"NGN\"}").andExpect(status().isOk());
        result(tunde, tundeGift, "{\"status\":\"FAILED\"}")
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("GIFT_ALREADY_REPORTED"));
        result(ada, forgotten, "{\"status\":\"CANCELLED\"}").andExpect(status().isNotFound());

        // An hour later the one nobody reported is UNKNOWN
        advance(Duration.ofMinutes(61));
        assertThat(expiry.expire()).isEqualTo(1);
        mvc.perform(auth(get("/api/v1/me/gifts"), tunde))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(2))
            .andExpect(jsonPath("$[?(@.id == '" + forgotten + "')].status").value("UNKNOWN"))
            .andExpect(jsonPath("$[?(@.id == '" + tundeGift + "')].amountMinor").value(250000))
            .andExpect(jsonPath("$[0].selfReported").value(true));

        // Hosts see totals, never who gave what
        mvc.perform(auth(get("/api/v1/spaces/" + spaceId + "/give/summary"), ada))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.promptsShown").value(1))
            .andExpect(jsonPath("$.giveTaps").value(3))
            .andExpect(jsonPath("$.completed").value(2))
            .andExpect(jsonPath("$.unreported").value(1))
            .andExpect(jsonPath("$.totals[0].currency").value("NGN"))
            .andExpect(jsonPath("$.totals[0].amountMinor").value(750000));
        mvc.perform(auth(get("/api/v1/spaces/" + spaceId + "/give/summary"), tunde)).andExpect(status().isForbidden());

        // Closing it takes it down
        mvc.perform(auth(delete("/api/v1/spaces/" + spaceId + "/give/prompts/" + promptId), ada)).andExpect(status().isNoContent());
        mvc.perform(auth(get("/api/v1/spaces/" + spaceId + "/give/prompts/current"), bisi)).andExpect(status().isNoContent());
        mvc.perform(json(post("/api/v1/spaces/" + spaceId + "/give/prompts/" + promptId + "/open"), bisi, "{}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("PROMPT_CLOSED"));
    }

    @Test
    void theAiWorkersCuesGoThroughTheSameChecks() throws Exception {
        String ada = register("Ada");
        String spaceId = createRoom(ada);
        String sessionId = startSession(ada, spaceId);

        cue(sessionId, LINK, "test-worker-token")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("SHOWN"));
        cue(sessionId, LINK, "test-worker-token")
            .andExpect(jsonPath("$.status").value("REJECTED"))
            .andExpect(jsonPath("$.reason").value("RECENTLY_SHOWN"));
        cue(sessionId, "https://evilpayaza.africa/pay", "test-worker-token")
            .andExpect(jsonPath("$.reason").value("NOT_PAYAZA"));
        cue(sessionId, LINK, "wrong").andExpect(status().isUnauthorized());
        mvc.perform(auth(get("/api/v1/spaces/" + spaceId + "/give/prompts/current"), ada))
            .andExpect(jsonPath("$.source").value("AI"));

        // The host blocks it here: taken down, and the AI can't bring it back
        mvc.perform(json(post("/api/v1/spaces/" + spaceId + "/give/blocked-links"), ada, "{\"url\":\"" + LINK + "\"}"))
            .andExpect(status().isCreated());
        mvc.perform(auth(get("/api/v1/spaces/" + spaceId + "/give/prompts/current"), ada)).andExpect(status().isNoContent());
        advance(Duration.ofMinutes(11));
        cue(sessionId, LINK, "test-worker-token").andExpect(jsonPath("$.reason").value("BLOCKED_IN_ROOM"));
        mvc.perform(auth(get("/api/v1/spaces/" + spaceId + "/give/blocked-links"), ada))
            .andExpect(jsonPath("$[0].url").value(LINK))
            .andExpect(jsonPath("$[0].blockedBy.displayName").value("Ada"));
    }

    @Test
    void threeScamReportsBlockALinkEverywhere() throws Exception {
        String scam = "https://pay.payaza.africa/give/scam-" + UUID.randomUUID();
        String ada = register("Ada");
        String spaceId = createRoom(ada);
        String invite = inviteOf(ada, spaceId);
        String tunde = register("Tunde");
        join(tunde, invite);
        String bisi = guestToken(invite, "Bisi");
        startSession(ada, spaceId);
        String promptId = read(showPrompt(ada, spaceId, scam).andReturn(), "$.id");

        report(tunde, spaceId, promptId);
        report(tunde, spaceId, promptId);
        report(bisi, spaceId, promptId);
        mvc.perform(auth(get("/api/v1/spaces/" + spaceId + "/give/prompts/current"), ada)).andExpect(status().isOk());
        report(ada, spaceId, promptId);
        mvc.perform(auth(get("/api/v1/spaces/" + spaceId + "/give/prompts/current"), ada)).andExpect(status().isNoContent());

        // Another room can't show it either
        String kemi = register("Kemi");
        String otherRoom = createRoom(kemi);
        startSession(kemi, otherRoom);
        showPrompt(kemi, otherRoom, scam)
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("LINK_BLOCKED"));
    }

    // ---- helpers -------------------------------------------------------------

    private ResultActions showPrompt(String token, String spaceId, String url) throws Exception {
        return mvc.perform(json(post("/api/v1/spaces/" + spaceId + "/give/prompts"), token, "{\"url\":\"" + url + "\"}"));
    }

    private String openGift(String token, String spaceId, String promptId, boolean anonymous) throws Exception {
        return read(mvc.perform(json(post("/api/v1/spaces/" + spaceId + "/give/prompts/" + promptId + "/open"), token,
                "{\"anonymous\":" + anonymous + "}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.checkoutUrl").value(LINK))
            .andReturn(), "$.giftId");
    }

    private ResultActions result(String token, String giftId, String body) throws Exception {
        return mvc.perform(json(post("/api/v1/gifts/" + giftId + "/result"), token, body));
    }

    private void report(String token, String spaceId, String promptId) throws Exception {
        mvc.perform(auth(post("/api/v1/spaces/" + spaceId + "/give/prompts/" + promptId + "/report"), token))
            .andExpect(status().isNoContent());
    }

    private ResultActions cue(String sessionId, String raw, String token) throws Exception {
        return mvc.perform(post("/api/v1/internal/cues").header("X-Service-Token", token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"sessionId\":\"" + sessionId + "\",\"rawValue\":\"" + raw + "\",\"confidence\":0.98}"));
    }

    private String startSession(String token, String spaceId) throws Exception {
        return read(mvc.perform(json(post("/api/v1/spaces/" + spaceId + "/sessions/watch-now"), token, "{}"))
            .andExpect(status().isCreated()).andReturn(), "$.id");
    }

    private String register(String name) throws Exception {
        return read(mvc.perform(json(post("/api/v1/auth/register"), null, """
                {"email":"%s-%s@example.com","password":"correct-horse-battery","displayName":"%s"}"""
                .formatted(name.toLowerCase(), UUID.randomUUID(), name)))
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
