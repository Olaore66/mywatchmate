package com.mywatchmate.connect.call;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.mywatchmate.connect.call.dto.CallParticipant;
import com.nimbusds.jwt.SignedJWT;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Calls over HTTP with the whole app, LiveKit itself faked. Not rolled back: removing someone from the
 * call happens after the removal is committed, as in production (rows are left with random emails).
 */
@SpringBootTest(properties = {
    "mywatchmate.livekit.url=wss://mywatchmate-test.livekit.cloud",
    "mywatchmate.livekit.api-key=" + LiveKitTokensTest.KEY,
    "mywatchmate.livekit.api-secret=" + LiveKitTokensTest.SECRET})
@AutoConfigureMockMvc
class CallFlowTests {

    @Autowired MockMvc mvc;
    @MockitoBean LiveKitRoomClient livekit;

    @Test
    void membersJoinTheCallTheRoomHearsWhosOnAndRemovedPeopleAreCutOff() throws Exception {
        String ada = register("Ada");
        String spaceId = read(mvc.perform(json(post("/api/v1/spaces"), ada, "{\"title\":\"Family call\",\"audienceType\":\"FRIENDS\"}")).andExpect(status().isCreated()).andReturn(), "$.id");
        String invite = read(mvc.perform(auth(get("/api/v1/spaces/" + spaceId + "/invite"), ada)).andReturn(), "$.inviteToken");
        MvcResult guest = mvc.perform(json(post("/api/v1/auth/guest"), null,
            "{\"inviteToken\":\"%s\",\"displayName\":\"Bisi\"}".formatted(invite))).andReturn();
        String bisi = read(guest, "$.accessToken");
        String bisiId = read(guest, "$.participantId");
        String room = "space-" + spaceId;

        // Bisi (a guest) gets a pass for this room's call only
        MvcResult pass = mvc.perform(auth(post("/api/v1/spaces/" + spaceId + "/call/join"), bisi))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.url").value("wss://mywatchmate-test.livekit.cloud"))
            .andExpect(jsonPath("$.roomName").value(room))
            .andExpect(jsonPath("$.identity").value(bisiId))
            .andReturn();
        var claims = SignedJWT.parse(read(pass, "$.token")).getJWTClaimsSet();
        assertThat(claims.getSubject()).isEqualTo(bisiId);
        assertThat(claims.getStringClaim("name")).isEqualTo("Bisi");
        assertThat(claims.getJSONObjectClaim("video")).containsEntry("room", room).containsEntry("roomJoin", true);

        // Outsiders get nothing
        String kemi = register("Kemi");
        mvc.perform(auth(post("/api/v1/spaces/" + spaceId + "/call/join"), kemi)).andExpect(status().isNotFound());

        // LiveKit says Bisi joined: the roster is asked for (and sent to the room as CALL_UPDATED)
        when(livekit.listParticipants(room)).thenReturn(List.of(
            new CallParticipant(UUID.fromString(bisiId), "Bisi", Instant.now(), true, true, false)));
        String body = "{\"event\":\"participant_joined\",\"room\":{\"name\":\"%s\"}}".formatted(room);
        mvc.perform(post(LiveKitWebhookController.PATH)
                .header("Authorization", LiveKitTokensTest.webhookToken(body, LiveKitTokensTest.KEY, LiveKitTokensTest.SECRET, Instant.now()))
                .contentType("application/webhook+json").content(body))
            .andExpect(status().isOk());
        mvc.perform(post(LiveKitWebhookController.PATH).header("Authorization", "forged")
                .contentType("application/webhook+json").content(body))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("INVALID_WEBHOOK_SIGNATURE"));
        mvc.perform(auth(get("/api/v1/spaces/" + spaceId + "/call"), ada))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.count").value(1))
            .andExpect(jsonPath("$.participants[0].displayName").value("Bisi"))
            .andExpect(jsonPath("$.participants[0].camera").value(true));

        // Ada removes Bisi from the room: she's taken out of the call too
        mvc.perform(auth(delete("/api/v1/spaces/" + spaceId + "/participants/" + bisiId), ada)).andExpect(status().isNoContent());
        verify(livekit, timeout(2000)).removeParticipant(room, bisiId);
    }

    // ---- helpers -------------------------------------------------------------

    private String register(String name) throws Exception {
        return read(mvc.perform(json(post("/api/v1/auth/register"), null, """
                {"email":"call-%s-%s@example.com","password":"correct-horse-battery","displayName":"%s"}"""
                .formatted(name.toLowerCase(), UUID.randomUUID(), name)))
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
