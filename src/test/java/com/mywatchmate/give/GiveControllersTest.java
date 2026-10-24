package com.mywatchmate.give;

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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mywatchmate.auth.AuthPrincipal;
import com.mywatchmate.common.ApiException;
import com.mywatchmate.give.dto.BlockedLinkResponse;
import com.mywatchmate.give.dto.CueOutcome;
import com.mywatchmate.give.dto.GiftResponse;
import com.mywatchmate.give.dto.GiveSummary;
import com.mywatchmate.give.dto.OpenGiftResponse;
import com.mywatchmate.give.dto.PromptResponse;
import com.mywatchmate.web.WebLayerTestConfig;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest({GiveController.class, GiftController.class, InternalCueController.class})
@Import(WebLayerTestConfig.class)
class GiveControllersTest {

    private static final UUID USER = UUID.randomUUID();
    private static final UUID SPACE = UUID.randomUUID();
    private static final UUID PROMPT = UUID.randomUUID();
    private static final Instant T0 = Instant.parse("2026-11-02T05:00:00Z");
    private static final String LINK = "https://pay.payaza.africa/give/stmarks";

    @Autowired MockMvc mvc;
    @MockitoBean GiveService give;
    @MockitoBean GiftService gifts;
    @MockitoBean GiveProperties props;

    private static PromptResponse prompt() {
        return new PromptResponse(PROMPT, UUID.randomUUID(), LINK, PaymentLink.Provider.PAYAZA, null,
            CueDetection.Source.HOST, T0, T0.plusSeconds(300), "You're paying …");
    }

    @Test
    void hostsShowAPrompt() throws Exception {
        when(give.showByHost(AuthPrincipal.user(USER), SPACE, LINK)).thenReturn(prompt());

        mvc.perform(post("/api/v1/spaces/" + SPACE + "/give/prompts").with(asUser(USER))
                .contentType(MediaType.APPLICATION_JSON).content("{\"url\":\"" + LINK + "\"}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.id").value(PROMPT.toString()))
            .andExpect(jsonPath("$.source").value("HOST"))
            .andExpect(jsonPath("$.notice").exists());
    }

    @Test
    void aBlankLinkIsRejectedAndServiceRefusalsKeepTheirCodes() throws Exception {
        mvc.perform(post("/api/v1/spaces/" + SPACE + "/give/prompts").with(asUser(USER))
                .contentType(MediaType.APPLICATION_JSON).content("{\"url\":\" \"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errors.url").exists());
        when(give.showByHost(any(), eq(SPACE), any()))
            .thenThrow(ApiException.unprocessable("UNSUPPORTED_PAYMENT_LINK", "nope"))
            .thenThrow(ApiException.conflict("NO_LIVE_SESSION", "nope"));

        mvc.perform(post("/api/v1/spaces/" + SPACE + "/give/prompts").with(asUser(USER))
                .contentType(MediaType.APPLICATION_JSON).content("{\"url\":\"https://x.com/a\"}"))
            .andExpect(status().isUnprocessableContent())
            .andExpect(jsonPath("$.code").value("UNSUPPORTED_PAYMENT_LINK"));
        mvc.perform(post("/api/v1/spaces/" + SPACE + "/give/prompts").with(asUser(USER))
                .contentType(MediaType.APPLICATION_JSON).content("{\"url\":\"" + LINK + "\"}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("NO_LIVE_SESSION"));
    }

    @Test
    void theCurrentPromptOrNothing() throws Exception {
        UUID bisi = UUID.randomUUID();
        when(give.current(AuthPrincipal.guest(bisi, SPACE), SPACE)).thenReturn(Optional.of(prompt()), Optional.empty());

        mvc.perform(get("/api/v1/spaces/" + SPACE + "/give/prompts/current").with(asGuest(bisi, SPACE)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.url").value(LINK));
        mvc.perform(get("/api/v1/spaces/" + SPACE + "/give/prompts/current").with(asGuest(bisi, SPACE)))
            .andExpect(status().isNoContent());
    }

    @Test
    void closingReportingAndBlocking() throws Exception {
        mvc.perform(delete("/api/v1/spaces/" + SPACE + "/give/prompts/" + PROMPT).with(asUser(USER)))
            .andExpect(status().isNoContent());
        mvc.perform(post("/api/v1/spaces/" + SPACE + "/give/prompts/" + PROMPT + "/report").with(asUser(USER)))
            .andExpect(status().isNoContent());
        mvc.perform(post("/api/v1/spaces/" + SPACE + "/give/prompts/" + PROMPT + "/report").with(asUser(USER))
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Not our church\"}"))
            .andExpect(status().isNoContent());
        verify(give).close(AuthPrincipal.user(USER), SPACE, PROMPT);
        verify(give).report(AuthPrincipal.user(USER), SPACE, PROMPT, null);
        verify(give).report(AuthPrincipal.user(USER), SPACE, PROMPT, "Not our church");

        UUID linkId = UUID.randomUUID();
        when(give.block(eq(AuthPrincipal.user(USER)), eq(SPACE), any()))
            .thenReturn(new BlockedLinkResponse(linkId, LINK, "scam", null, T0));
        when(give.blockedLinks(AuthPrincipal.user(USER), SPACE)).thenReturn(List.of());
        mvc.perform(post("/api/v1/spaces/" + SPACE + "/give/blocked-links").with(asUser(USER))
                .contentType(MediaType.APPLICATION_JSON).content("{\"url\":\"" + LINK + "\",\"reason\":\"scam\"}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.paymentLinkId").value(linkId.toString()));
        mvc.perform(get("/api/v1/spaces/" + SPACE + "/give/blocked-links").with(asUser(USER)))
            .andExpect(status().isOk());
        mvc.perform(delete("/api/v1/spaces/" + SPACE + "/give/blocked-links/" + linkId).with(asUser(USER)))
            .andExpect(status().isNoContent());
        verify(give).unblock(AuthPrincipal.user(USER), SPACE, linkId);
    }

    @Test
    void tappingGiveAnonymouslyOrNot() throws Exception {
        UUID gift = UUID.randomUUID();
        OpenGiftResponse opened = new OpenGiftResponse(gift, LINK, PaymentLink.Provider.PAYAZA, null, "You're paying …");
        when(gifts.open(any(), eq(SPACE), eq(PROMPT), any(Boolean.class))).thenReturn(opened);

        mvc.perform(post("/api/v1/spaces/" + SPACE + "/give/prompts/" + PROMPT + "/open").with(asUser(USER))
                .contentType(MediaType.APPLICATION_JSON).content("{\"anonymous\":true}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.checkoutUrl").value(LINK))
            .andExpect(jsonPath("$.giftId").value(gift.toString()));
        mvc.perform(post("/api/v1/spaces/" + SPACE + "/give/prompts/" + PROMPT + "/open").with(asUser(USER)))
            .andExpect(status().isCreated());
        verify(gifts).open(AuthPrincipal.user(USER), SPACE, PROMPT, true);
        verify(gifts).open(AuthPrincipal.user(USER), SPACE, PROMPT, false);
    }

    @Test
    void reportingTheResult() throws Exception {
        UUID giftId = UUID.randomUUID();
        when(gifts.reportResult(eq(AuthPrincipal.user(USER)), eq(giftId), any())).thenReturn(new GiftResponse(giftId,
            SPACE, "Room", UUID.randomUUID(), LINK, null, Gift.Status.COMPLETED, 500_000L, "NGN", "PZ-1", false, T0, T0, true));

        mvc.perform(post("/api/v1/gifts/" + giftId + "/result").with(asUser(USER)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"COMPLETED\",\"amountMinor\":500000,\"currency\":\"NGN\",\"providerTxnRef\":\"PZ-1\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("COMPLETED"))
            .andExpect(jsonPath("$.selfReported").value(true));
        verify(gifts).reportResult(eq(AuthPrincipal.user(USER)), eq(giftId),
            argThat(r -> r.amountMinor() == 500_000L && "NGN".equals(r.currency())));
    }

    @Test
    void badResultsAreRejectedBeforeTheService() throws Exception {
        UUID giftId = UUID.randomUUID();
        for (String body : new String[] {"{}", "{\"status\":\"OPENED\"}", "{\"status\":\"COMPLETED\",\"amountMinor\":0,\"currency\":\"NGN\"}",
            "{\"status\":\"COMPLETED\",\"amountMinor\":5,\"currency\":\"naira\"}"}) {
            mvc.perform(post("/api/v1/gifts/" + giftId + "/result").with(asUser(USER))
                    .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(gifts);
    }

    @Test
    void myGiftsAndTheSummary() throws Exception {
        when(gifts.mine(USER)).thenReturn(List.of());
        when(gifts.summary(AuthPrincipal.user(USER), SPACE, T0, null)).thenReturn(new GiveSummary(T0, T0.plus(Duration.ofDays(1)),
            2, 5, 3, 1, 1, List.of(new GiveSummary.CurrencyTotal("NGN", 750_000, 3)), true));

        mvc.perform(get("/api/v1/me/gifts").with(asUser(USER))).andExpect(status().isOk());
        mvc.perform(get("/api/v1/spaces/" + SPACE + "/give/summary").param("from", T0.toString()).with(asUser(USER)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totals[0].currency").value("NGN"))
            .andExpect(jsonPath("$.totals[0].amountMinor").value(750_000))
            .andExpect(jsonPath("$.selfReported").value(true));
        mvc.perform(get("/api/v1/me/gifts").with(asGuest(UUID.randomUUID(), SPACE))).andExpect(status().isForbidden());
    }

    @Test
    void theAiWorkerNeedsTheServiceToken() throws Exception {
        UUID session = UUID.randomUUID();
        String body = "{\"sessionId\":\"" + session + "\",\"rawValue\":\"" + LINK + "\",\"confidence\":0.97}";
        when(props.internalToken()).thenReturn("worker-secret");
        when(give.fromWorker(any())).thenReturn(new CueOutcome(PROMPT, CueDetection.Status.SHOWN, null));

        mvc.perform(post("/api/v1/internal/cues").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("INVALID_SERVICE_TOKEN"));
        mvc.perform(post("/api/v1/internal/cues").header("X-Service-Token", "wrong")
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isUnauthorized());
        // A user's access token is not a service token
        mvc.perform(post("/api/v1/internal/cues").with(asUser(USER)).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/internal/cues").header("X-Service-Token", "worker-secret")
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("SHOWN"));
        verify(give).fromWorker(argThat(r -> r.sessionId().equals(session) && r.rawValue().equals(LINK)));
    }

    @Test
    void internalEndpointsAreOffWithoutAToken() throws Exception {
        when(props.internalToken()).thenReturn("");

        mvc.perform(post("/api/v1/internal/cues").header("X-Service-Token", "")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"sessionId\":\"" + UUID.randomUUID() + "\",\"rawValue\":\"x\"}"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.code").value("INTERNAL_DISABLED"));
        verifyNoInteractions(give);
    }

    @Test
    void badCuesAreRejected() throws Exception {
        when(props.internalToken()).thenReturn("worker-secret");
        mvc.perform(post("/api/v1/internal/cues").header("X-Service-Token", "worker-secret")
                .contentType(MediaType.APPLICATION_JSON).content("{\"rawValue\":\"x\",\"confidence\":1.5}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errors.sessionId").exists())
            .andExpect(jsonPath("$.errors.confidence").exists());
    }

    @Test
    void giveNeedsSignIn() throws Exception {
        mvc.perform(get("/api/v1/spaces/" + SPACE + "/give/prompts/current")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/gifts/" + UUID.randomUUID() + "/result")).andExpect(status().isUnauthorized());
    }
}
