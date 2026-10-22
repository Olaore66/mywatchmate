package com.mywatchmate.watch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.mywatchmate.config.WebSocketAuthInterceptor;
import com.mywatchmate.watch.content.ResolvedLink;
import com.mywatchmate.watch.content.VideoMetadata;
import com.mywatchmate.watch.content.VideoMetadataClient;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

/** Whole room flows over HTTP against the real database (rolled back). YouTube is faked. */
@SpringBootTest(properties = "mywatchmate.spaces.max-members=4")
@AutoConfigureMockMvc
@Transactional
class SpaceFlowTests {

    private static final String VIDEO = "https://youtu.be/dQw4w9WgXcQ?si=share";

    @Autowired MockMvc mvc;
    @Autowired WebSocketAuthInterceptor wsInterceptor;
    @Autowired JwtDecoder jwtDecoder;
    @MockitoBean VideoMetadataClient metadata;

    @BeforeEach
    void fakeYoutube() {
        when(metadata.fetch(any(ResolvedLink.class)))
            .thenReturn(new VideoMetadata("Morning Prayer, Day 12", "https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg", null));
    }

    @Test
    void theWholeRoomLifecycle() throws Exception {
        String ada = register("Ada");
        String tunde = register("Tunde");

        // Ada creates a room with a video: it is playing straight away
        MvcResult created = mvc.perform(json(post("/api/v1/spaces"), ada, """
                {"title":"6am Prayer Room","audienceType":"DIASPORA","videoUrl":"%s"}""".formatted(VIDEO)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.myRole").value("OWNER"))
            .andExpect(jsonPath("$.memberCount").value(1))
            .andExpect(jsonPath("$.canAddContent").value(true))
            .andExpect(jsonPath("$.nowPlaying.source").value("YOUTUBE"))
            .andExpect(jsonPath("$.nowPlaying.url").value("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
            .andExpect(jsonPath("$.nowPlaying.title").value("Morning Prayer, Day 12"))
            .andExpect(jsonPath("$.nowPlaying.addedBy.displayName").value("Ada"))
            .andReturn();
        String spaceId = read(created, "$.id");

        // Share the link; anyone can preview it without signing in
        String invite = read(mvc.perform(auth(get("/api/v1/spaces/" + spaceId + "/invite"), ada))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.inviteUrl").value(org.hamcrest.Matchers.startsWith("http://localhost:3000/join/")))
            .andReturn(), "$.inviteToken");
        mvc.perform(get("/api/v1/invites/" + invite))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.spaceTitle").value("6am Prayer Room"))
            .andExpect(jsonPath("$.ownerName").value("Ada"))
            .andExpect(jsonPath("$.memberCount").value(1))
            .andExpect(jsonPath("$.nowPlayingTitle").value("Morning Prayer, Day 12"));

        // Tunde joins (twice is harmless); Bisi joins as a guest
        mvc.perform(auth(post("/api/v1/invites/" + invite + "/join"), tunde))
            .andExpect(status().isOk()).andExpect(jsonPath("$.myRole").value("MEMBER"))
            .andExpect(jsonPath("$.nowPlaying.title").value("Morning Prayer, Day 12"));
        mvc.perform(auth(post("/api/v1/invites/" + invite + "/join"), tunde))
            .andExpect(status().isOk()).andExpect(jsonPath("$.memberCount").value(2));
        MvcResult bisiJoined = mvc.perform(json(post("/api/v1/auth/guest"), null, """
                {"inviteToken":"%s","displayName":"Bisi"}""".formatted(invite)))
            .andExpect(status().isCreated()).andReturn();
        String bisi = read(bisiJoined, "$.accessToken");

        // Everyone, guests included, sees the room and who's in it
        mvc.perform(auth(get("/api/v1/spaces/" + spaceId), bisi))
            .andExpect(status().isOk()).andExpect(jsonPath("$.myRole").value("GUEST"))
            .andExpect(jsonPath("$.canAddContent").value(false));
        MvcResult people = mvc.perform(auth(get("/api/v1/spaces/" + spaceId + "/participants"), bisi))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[*].displayName").value(org.hamcrest.Matchers.contains("Ada", "Tunde", "Bisi")))
            .andReturn();
        String tundeId = participantId(people, "Tunde");
        String bisiId = participantId(people, "Bisi");

        // Ada makes Tunde an admin; Tunde removes Bisi, whose guest session ends
        mvc.perform(json(patch("/api/v1/spaces/" + spaceId + "/participants/" + tundeId), ada, """
                {"role":"ADMIN"}"""))
            .andExpect(status().isOk()).andExpect(jsonPath("$.role").value("ADMIN"));
        mvc.perform(auth(delete("/api/v1/spaces/" + spaceId + "/participants/" + bisiId), tunde))
            .andExpect(status().isNoContent());
        mvc.perform(json(post("/api/v1/auth/guest/refresh"), null, """
                {"deviceToken":"%s"}""".formatted(read(bisiJoined, "$.deviceToken"))))
            .andExpect(status().isUnauthorized());
        mvc.perform(auth(get("/api/v1/spaces/" + spaceId), bisi))
            .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("SPACE_NOT_FOUND"));

        // Ada can't leave as owner; she hands over to Tunde and becomes admin
        mvc.perform(auth(post("/api/v1/spaces/" + spaceId + "/leave"), ada))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("OWNER_MUST_TRANSFER"));
        mvc.perform(json(post("/api/v1/spaces/" + spaceId + "/transfer-ownership"), ada, """
                {"participantId":"%s"}""".formatted(tundeId)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.myRole").value("ADMIN"))
            .andExpect(jsonPath("$.owner.displayName").value("Tunde"));

        // Tunde archives: the room and its link disappear for everyone else; he can restore it
        mvc.perform(auth(post("/api/v1/spaces/" + spaceId + "/archive"), tunde))
            .andExpect(status().isOk()).andExpect(jsonPath("$.archivedAt").isNotEmpty());
        mvc.perform(auth(get("/api/v1/spaces/" + spaceId), ada)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/invites/" + invite)).andExpect(status().isNotFound());
        mvc.perform(auth(post("/api/v1/spaces/" + spaceId + "/restore"), tunde))
            .andExpect(status().isOk()).andExpect(jsonPath("$.archivedAt").doesNotExist());
        mvc.perform(auth(get("/api/v1/spaces/" + spaceId), ada)).andExpect(status().isOk());
    }

    @Test
    void outsidersCantSeeOrTouchARoom() throws Exception {
        String ada = register("Ada");
        String kemi = register("Kemi");
        String spaceId = createRoom(ada, "Private");

        mvc.perform(auth(get("/api/v1/spaces/" + spaceId), kemi))
            .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("SPACE_NOT_FOUND"));
        mvc.perform(json(patch("/api/v1/spaces/" + spaceId), kemi, "{\"title\":\"Mine\"}")).andExpect(status().isNotFound());
        mvc.perform(auth(get("/api/v1/spaces/" + spaceId + "/participants"), kemi)).andExpect(status().isNotFound());
        mvc.perform(auth(get("/api/v1/spaces/" + UUID.randomUUID()), ada)).andExpect(status().isNotFound());
    }

    @Test
    void myRoomsListActiveAndArchivedSeparately() throws Exception {
        String ada = register("Ada");
        String first = createRoom(ada, "First");
        String second = createRoom(ada, "Second");
        mvc.perform(json(post("/api/v1/spaces/" + first + "/content"), ada, "{\"url\":\"%s\"}".formatted(VIDEO)))
            .andExpect(status().isCreated());
        mvc.perform(auth(post("/api/v1/spaces/" + second + "/archive"), ada)).andExpect(status().isOk());

        mvc.perform(auth(get("/api/v1/spaces"), ada))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].title").value("First"))
            .andExpect(jsonPath("$[0].myRole").value("OWNER"))
            .andExpect(jsonPath("$[0].memberCount").value(1))
            .andExpect(jsonPath("$[0].nowPlayingTitle").value("Morning Prayer, Day 12"));
        mvc.perform(auth(get("/api/v1/spaces?archived=true"), ada))
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].title").value("Second"));
    }

    @Test
    void guestsCantCreateOrListRoomsOrSeeTheLink() throws Exception {
        String ada = register("Ada");
        String spaceId = createRoom(ada, "Room");
        String guest = guestToken(inviteOf(ada, spaceId), "Bisi");

        mvc.perform(json(post("/api/v1/spaces"), guest, "{\"title\":\"x\",\"audienceType\":\"OTHER\"}"))
            .andExpect(status().isForbidden());
        mvc.perform(auth(get("/api/v1/spaces"), guest)).andExpect(status().isForbidden());
        mvc.perform(auth(get("/api/v1/spaces/" + spaceId + "/invite"), guest))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN_ROLE"));
    }

    @Test
    void videosAddPlaySwitchAndRemove() throws Exception {
        String ada = register("Ada");
        String tunde = register("Tunde");
        String spaceId = createRoom(ada, "Room");
        mvc.perform(auth(post("/api/v1/invites/" + inviteOf(ada, spaceId) + "/join"), tunde)).andExpect(status().isOk());

        // Preview first, like the frontend will
        mvc.perform(json(post("/api/v1/content/preview"), tunde, "{\"url\":\"%s\"}".formatted(VIDEO)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.externalId").value("dQw4w9WgXcQ"));
        mvc.perform(json(post("/api/v1/content/preview"), tunde, "{\"url\":\"https://www.tiktok.com/@a/video/1\"}"))
            .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("UNSUPPORTED_LINK"));

        // Members can't add until the owner allows it
        mvc.perform(json(post("/api/v1/spaces/" + spaceId + "/content"), tunde, "{\"url\":\"%s\"}".formatted(VIDEO)))
            .andExpect(status().isForbidden());
        mvc.perform(json(patch("/api/v1/spaces/" + spaceId), ada, "{\"membersCanAddContent\":true}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.membersCanAddContent").value(true));
        String tundesVideo = read(mvc.perform(json(post("/api/v1/spaces/" + spaceId + "/content"), tunde,
                "{\"url\":\"%s\"}".formatted(VIDEO)))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.addedBy.displayName").value("Tunde"))
            .andReturn(), "$.id");
        String adasVideo = read(mvc.perform(json(post("/api/v1/spaces/" + spaceId + "/content"), ada,
                "{\"url\":\"https://vimeo.com/76979871\",\"playNow\":false}"))
            .andExpect(status().isCreated()).andReturn(), "$.id");

        // Tunde's is playing (Ada's was added without playing); switch, list, remove
        mvc.perform(auth(get("/api/v1/spaces/" + spaceId), ada)).andExpect(jsonPath("$.nowPlaying.id").value(tundesVideo));
        mvc.perform(json(put("/api/v1/spaces/" + spaceId + "/now-playing"), ada, "{\"contentId\":\"%s\"}".formatted(adasVideo)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.source").value("VIMEO"));
        mvc.perform(auth(get("/api/v1/spaces/" + spaceId + "/content"), tunde))
            .andExpect(jsonPath("$.length()").value(2));
        mvc.perform(auth(delete("/api/v1/spaces/" + spaceId + "/content/" + adasVideo), tunde))
            .andExpect(status().isForbidden());
        mvc.perform(auth(delete("/api/v1/spaces/" + spaceId + "/content/" + adasVideo), ada))
            .andExpect(status().isNoContent());
        mvc.perform(auth(get("/api/v1/spaces/" + spaceId), ada)).andExpect(jsonPath("$.nowPlaying").doesNotExist());
    }

    @Test
    void removedUsersCantRejoinAndRotatingKillsTheOldLink() throws Exception {
        String ada = register("Ada");
        String tunde = register("Tunde");
        String spaceId = createRoom(ada, "Room");
        String invite = inviteOf(ada, spaceId);
        mvc.perform(auth(post("/api/v1/invites/" + invite + "/join"), tunde)).andExpect(status().isOk());
        String tundeId = participantId(mvc.perform(auth(get("/api/v1/spaces/" + spaceId + "/participants"), ada)).andReturn(), "Tunde");

        mvc.perform(auth(delete("/api/v1/spaces/" + spaceId + "/participants/" + tundeId), ada)).andExpect(status().isNoContent());
        mvc.perform(auth(post("/api/v1/invites/" + invite + "/join"), tunde))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("REMOVED_FROM_SPACE"));

        String fresh = read(mvc.perform(auth(post("/api/v1/spaces/" + spaceId + "/invite/rotate"), ada))
            .andExpect(status().isOk()).andReturn(), "$.inviteToken");
        assertThat(fresh).isNotEqualTo(invite);
        mvc.perform(get("/api/v1/invites/" + invite)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/invites/" + fresh)).andExpect(status().isOk());
    }

    @Test
    void roomsHaveAMemberLimit() throws Exception {
        String ada = register("Ada");
        String spaceId = createRoom(ada, "Small room");
        String invite = inviteOf(ada, spaceId);
        for (String name : List.of("A", "B", "C")) {
            guestToken(invite, name);
        }

        mvc.perform(get("/api/v1/invites/" + invite)).andExpect(jsonPath("$.full").value(true));
        mvc.perform(json(post("/api/v1/auth/guest"), null, "{\"inviteToken\":\"%s\",\"displayName\":\"D\"}".formatted(invite)))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SPACE_FULL"));
        mvc.perform(auth(post("/api/v1/invites/" + invite + "/join"), register("Late")))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SPACE_FULL"));
    }

    @Test
    void onlyMembersCanSubscribeToARoomsLiveEvents() throws Exception {
        String ada = register("Ada");
        String kemi = register("Kemi");
        String spaceId = createRoom(ada, "Room");
        String topic = "/topic/spaces/" + spaceId + "/events";

        Message<byte[]> member = subscribe(ada, topic);
        assertThat(wsInterceptor.preSend(member, null)).isSameAs(member);
        assertThatThrownBy(() -> wsInterceptor.preSend(subscribe(kemi, topic), null))
            .isInstanceOf(AccessDeniedException.class);
    }

    // ---- helpers -------------------------------------------------------------

    private String register(String name) throws Exception {
        return read(mvc.perform(json(post("/api/v1/auth/register"), null, """
                {"email":"%s-%s@example.com","password":"correct-horse-battery","displayName":"%s"}"""
                .formatted(name.toLowerCase(), UUID.randomUUID(), name)))
            .andExpect(status().isCreated()).andReturn(), "$.accessToken");
    }

    private String createRoom(String token, String title) throws Exception {
        return read(mvc.perform(json(post("/api/v1/spaces"), token, """
                {"title":"%s","audienceType":"FRIENDS"}""".formatted(title)))
            .andExpect(status().isCreated()).andReturn(), "$.id");
    }

    private String inviteOf(String token, String spaceId) throws Exception {
        return read(mvc.perform(auth(get("/api/v1/spaces/" + spaceId + "/invite"), token)).andReturn(), "$.inviteToken");
    }

    private String guestToken(String invite, String name) throws Exception {
        return read(mvc.perform(json(post("/api/v1/auth/guest"), null, """
                {"inviteToken":"%s","displayName":"%s"}""".formatted(invite, name)))
            .andExpect(status().isCreated()).andReturn(), "$.accessToken");
    }

    private Message<byte[]> subscribe(String token, String destination) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setUser(new com.mywatchmate.auth.JwtPrincipalConverter().convert(jwtDecoder.decode(token)));
        accessor.setDestination(destination);
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    private static String participantId(MvcResult people, String name) throws Exception {
        List<String> ids = JsonPath.read(people.getResponse().getContentAsString(), "$[?(@.displayName == '" + name + "')].id");
        return ids.get(0);
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
