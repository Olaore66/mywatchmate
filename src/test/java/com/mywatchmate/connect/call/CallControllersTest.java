package com.mywatchmate.connect.call;

import static com.mywatchmate.web.WebLayerTestConfig.asGuest;
import static com.mywatchmate.web.WebLayerTestConfig.asUser;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mywatchmate.auth.AuthPrincipal;
import com.mywatchmate.common.ApiException;
import com.mywatchmate.connect.call.dto.CallJoinResponse;
import com.mywatchmate.connect.call.dto.CallParticipant;
import com.mywatchmate.connect.call.dto.CallRoster;
import com.mywatchmate.web.WebLayerTestConfig;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest({CallController.class, LiveKitWebhookController.class})
@Import(WebLayerTestConfig.class)
class CallControllersTest {

    private static final UUID USER = UUID.randomUUID();
    private static final UUID SPACE = UUID.randomUUID();
    private static final Instant T0 = Instant.parse("2026-11-02T05:00:00Z");

    @Autowired MockMvc mvc;
    @MockitoBean CallService calls;

    @Test
    void guestsGetAPassToo() throws Exception {
        UUID bisi = UUID.randomUUID();
        when(calls.join(AuthPrincipal.guest(bisi, SPACE), SPACE)).thenReturn(new CallJoinResponse(
            "wss://x.livekit.cloud", "jwt", "space-" + SPACE, bisi.toString(), T0.plusSeconds(600)));

        mvc.perform(post("/api/v1/spaces/" + SPACE + "/call/join").with(asGuest(bisi, SPACE)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.url").value("wss://x.livekit.cloud"))
            .andExpect(jsonPath("$.token").value("jwt"))
            .andExpect(jsonPath("$.identity").value(bisi.toString()))
            .andExpect(jsonPath("$.roomName").value("space-" + SPACE));
    }

    @Test
    void theRosterAndItsErrors() throws Exception {
        UUID bisi = UUID.randomUUID();
        when(calls.roster(AuthPrincipal.user(USER), SPACE))
            .thenReturn(new CallRoster(1, List.of(new CallParticipant(bisi, "Bisi", T0, true, false, false))))
            .thenThrow(new ApiException(HttpStatus.BAD_GATEWAY, "CALL_SERVICE_UNAVAILABLE", "down"))
            .thenThrow(new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "CALLS_NOT_CONFIGURED", "off"));

        mvc.perform(get("/api/v1/spaces/" + SPACE + "/call").with(asUser(USER)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.count").value(1))
            .andExpect(jsonPath("$.participants[0].displayName").value("Bisi"))
            .andExpect(jsonPath("$.participants[0].camera").value(true));
        mvc.perform(get("/api/v1/spaces/" + SPACE + "/call").with(asUser(USER)))
            .andExpect(status().isBadGateway())
            .andExpect(jsonPath("$.code").value("CALL_SERVICE_UNAVAILABLE"));
        mvc.perform(get("/api/v1/spaces/" + SPACE + "/call").with(asUser(USER)))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.code").value("CALLS_NOT_CONFIGURED"));
    }

    @Test
    void callsNeedSignIn() throws Exception {
        mvc.perform(post("/api/v1/spaces/" + SPACE + "/call/join")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/spaces/" + SPACE + "/call")).andExpect(status().isUnauthorized());
    }

    @Test
    void livekitsWebhookReachesUsDespiteItsOwnAuthorizationHeader() throws Exception {
        // LiveKit's token isn't one of our login tokens; it must not be rejected as a bad login
        mvc.perform(post(LiveKitWebhookController.PATH).header("Authorization", "Bearer livekit.signed.token")
                .contentType("application/webhook+json").content("{\"event\":\"participant_joined\"}"))
            .andExpect(status().isOk());
        mvc.perform(post(LiveKitWebhookController.PATH).header("Authorization", "livekit.signed.token")
                .contentType("application/webhook+json").content("{}"))
            .andExpect(status().isOk());

        verify(calls).onWebhook("Bearer livekit.signed.token", "{\"event\":\"participant_joined\"}");
        verify(calls).onWebhook("livekit.signed.token", "{}");
    }

    @Test
    void aForgedWebhookIs401() throws Exception {
        doThrow(ApiException.unauthorized("INVALID_WEBHOOK_SIGNATURE", "no"))
            .when(calls).onWebhook(any(), eq("{}"));

        mvc.perform(post(LiveKitWebhookController.PATH).contentType("application/webhook+json").content("{}"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("INVALID_WEBHOOK_SIGNATURE"));
    }
}
