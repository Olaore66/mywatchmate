package com.mywatchmate.connect;

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
import java.util.ArrayList;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

/**
 * Chat over HTTP against the real (partitioned) table, rolled back. The clock is controlled: it stays still
 * while many messages are sent, so they share a timestamp, the hardest case for history paging.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ChatFlowTests {

    @Autowired MockMvc mvc;
    @MockitoBean Clock clock;
    @MockitoBean VideoMetadataClient metadata;

    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.now().truncatedTo(ChronoUnit.SECONDS));

    @BeforeEach
    void controlledWorld() {
        when(clock.instant()).thenAnswer(i -> now.get());
        when(metadata.fetch(any(ResolvedLink.class))).thenReturn(new VideoMetadata("Morning Prayer", null, null));
    }

    private void advance(Duration d) {
        now.set(now.get().plus(d));
    }

    @Test
    void chattingReadingAndUnreadBadges() throws Exception {
        String ada = register("Ada");
        String tunde = register("Tunde");
        String spaceId = createRoom(ada);
        String invite = inviteOf(ada, spaceId);
        join(tunde, invite);
        String bisi = guestToken(invite, "Bisi");
        advance(Duration.ofMinutes(1));

        // Between sessions: no session tag
        send(bisi, spaceId, "See you Sunday!").andExpect(status().isCreated())
            .andExpect(jsonPath("$.sessionId").doesNotExist())
            .andExpect(jsonPath("$.sender.displayName").value("Bisi"));
        advance(Duration.ofSeconds(10));

        // During a live session: tagged with it
        String sessionId = read(mvc.perform(json(post("/api/v1/spaces/" + spaceId + "/sessions/watch-now"), ada, "{}"))
            .andReturn(), "$.id");
        send(ada, spaceId, "Welcome everyone").andExpect(jsonPath("$.sessionId").value(sessionId));
        advance(Duration.ofSeconds(10));

        // Tunde hasn't read anything: two from others; his own messages never count
        mvc.perform(auth(get("/api/v1/spaces/" + spaceId), tunde)).andExpect(jsonPath("$.unreadCount").value(2));
        mvc.perform(auth(get("/api/v1/spaces"), tunde)).andExpect(jsonPath("$[0].unreadCount").value(2));
        send(tunde, spaceId, "Amen").andExpect(status().isCreated());
        mvc.perform(auth(get("/api/v1/spaces/" + spaceId), tunde)).andExpect(jsonPath("$.unreadCount").value(0));
        mvc.perform(auth(get("/api/v1/spaces/" + spaceId), ada)).andExpect(jsonPath("$.unreadCount").value(1));
        advance(Duration.ofSeconds(1));
        mvc.perform(auth(post("/api/v1/spaces/" + spaceId + "/chat/read"), ada)).andExpect(status().isNoContent());
        mvc.perform(auth(get("/api/v1/spaces"), ada)).andExpect(jsonPath("$[0].unreadCount").value(0));

        // History, oldest first
        mvc.perform(auth(get("/api/v1/spaces/" + spaceId + "/chat"), bisi))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.messages.length()").value(3))
            .andExpect(jsonPath("$.messages[0].body").value("See you Sunday!"))
            .andExpect(jsonPath("$.messages[2].body").value("Amen"))
            .andExpect(jsonPath("$.olderCursor").doesNotExist());

        // Bisi deletes her message; a deleted message no longer counts as unread
        String bisis = read(mvc.perform(auth(get("/api/v1/spaces/" + spaceId + "/chat"), bisi)).andReturn(), "$.messages[0].id");
        mvc.perform(auth(delete("/api/v1/spaces/" + spaceId + "/chat/" + bisis), tunde))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("NOT_YOUR_MESSAGE"));
        mvc.perform(auth(delete("/api/v1/spaces/" + spaceId + "/chat/" + bisis), bisi)).andExpect(status().isNoContent());
        mvc.perform(auth(get("/api/v1/spaces/" + spaceId + "/chat"), ada))
            .andExpect(jsonPath("$.messages[0].deleted").value(true))
            .andExpect(jsonPath("$.messages[0].body").doesNotExist())
            .andExpect(jsonPath("$.messages[0].deletedByHost").value(false));
        mvc.perform(auth(get("/api/v1/spaces/" + spaceId), tunde)).andExpect(jsonPath("$.unreadCount").value(0));
    }

    @Test
    void historyPagesNeverSkipOrRepeatEvenWithIdenticalTimestamps() throws Exception {
        String ada = register("Ada");
        String spaceId = createRoom(ada);
        List<String> sent = new ArrayList<>();
        for (int i = 0; i < 23; i++) {
            if (i % 5 == 4) {
                // A second passes now and then, so pages also cross real time boundaries
                advance(Duration.ofSeconds(1));
            }
            sent.add("message " + i);
            send(ada, spaceId, "message " + i).andExpect(status().isCreated());
            // Rate limit: 5 per 5 seconds, so let time pass between bursts
            if (i % 5 == 4) {
                advance(Duration.ofSeconds(5));
            }
        }

        List<String> seen = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            MockHttpServletRequestBuilder page = get("/api/v1/spaces/" + spaceId + "/chat").param("limit", "4");
            if (cursor != null) {
                page.param("before", cursor);
            }
            MvcResult result = mvc.perform(auth(page, ada)).andExpect(status().isOk()).andReturn();
            List<String> bodies = JsonPath.read(result.getResponse().getContentAsString(), "$.messages[*].body");
            seen.addAll(0, bodies);
            cursor = JsonPath.read(result.getResponse().getContentAsString(), "$.olderCursor");
            pages++;
        } while (cursor != null);

        assertThat(pages).isEqualTo(6);
        // Messages sent in the same microsecond have no "true" order between them; none may be lost or doubled
        assertThat(seen).hasSize(23).containsExactlyInAnyOrderElementsOf(sent);
    }

    @Test
    void hostsMuteAndTheRateLimitHolds() throws Exception {
        String ada = register("Ada");
        String tunde = register("Tunde");
        String spaceId = createRoom(ada);
        join(tunde, inviteOf(ada, spaceId));
        String tundeId = participantId(ada, spaceId, "Tunde");

        mvc.perform(json(post("/api/v1/spaces/" + spaceId + "/participants/" + tundeId + "/mute"), ada, "{\"minutes\":10}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.mutedUntil").value(now.get().plus(Duration.ofMinutes(10)).toString()));
        send(tunde, spaceId, "let me talk")
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("CHAT_MUTED"));
        mvc.perform(auth(get("/api/v1/spaces/" + spaceId), tunde))
            .andExpect(jsonPath("$.chatMuted").value(true))
            .andExpect(jsonPath("$.chatMutedUntil").exists());

        // The mute runs out on its own
        advance(Duration.ofMinutes(10));
        send(tunde, spaceId, "thanks").andExpect(status().isCreated());
        mvc.perform(auth(get("/api/v1/spaces/" + spaceId), tunde)).andExpect(jsonPath("$.chatMuted").value(false));

        // Muted until unmuted, then unmuted
        mvc.perform(auth(post("/api/v1/spaces/" + spaceId + "/participants/" + tundeId + "/mute"), ada))
            .andExpect(jsonPath("$.mutedUntil").doesNotExist());
        send(tunde, spaceId, "hello?").andExpect(status().isForbidden());
        mvc.perform(auth(delete("/api/v1/spaces/" + spaceId + "/participants/" + tundeId + "/mute"), ada))
            .andExpect(status().isNoContent());

        // Ada sends 5 quickly; the 6th within 5 seconds is refused
        for (int i = 0; i < 5; i++) {
            send(ada, spaceId, "quick " + i).andExpect(status().isCreated());
        }
        send(ada, spaceId, "one too many")
            .andExpect(status().isTooManyRequests())
            .andExpect(jsonPath("$.code").value("CHAT_RATE_LIMITED"));

        // Members can't mute
        String adaId = participantId(ada, spaceId, "Ada");
        mvc.perform(auth(post("/api/v1/spaces/" + spaceId + "/participants/" + adaId + "/mute"), tunde))
            .andExpect(status().isForbidden());
    }

    // ---- helpers -------------------------------------------------------------

    private ResultActions send(String token, String spaceId, String body) throws Exception {
        return mvc.perform(json(post("/api/v1/spaces/" + spaceId + "/chat"), token, "{\"body\":\"" + body + "\"}"));
    }

    private String participantId(String token, String spaceId, String name) throws Exception {
        MvcResult people = mvc.perform(auth(get("/api/v1/spaces/" + spaceId + "/participants"), token)).andReturn();
        List<String> ids = JsonPath.read(people.getResponse().getContentAsString(), "$[?(@.displayName == '" + name + "')].id");
        return ids.get(0);
    }

    private String register(String name) throws Exception {
        return read(mvc.perform(json(post("/api/v1/auth/register"), null, """
                {"email":"%s-%s@example.com","password":"correct-horse-battery","displayName":"%s"}"""
                .formatted(name.toLowerCase(), UUID.randomUUID(), name)))
            .andExpect(status().isCreated()).andReturn(), "$.accessToken");
    }

    private String createRoom(String token) throws Exception {
        return read(mvc.perform(json(post("/api/v1/spaces"), token, """
                {"title":"Prayer Room","audienceType":"DIASPORA","videoUrl":"https://youtu.be/dQw4w9WgXcQ"}"""))
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
