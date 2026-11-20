package com.mywatchmate.give;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.mywatchmate.watch.content.ResolvedLink;
import com.mywatchmate.watch.content.VideoMetadata;
import com.mywatchmate.watch.content.VideoMetadataClient;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

/** Giving v2 over HTTP with the real database (rolled back); only Payaza itself is stood in for. */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class InRoomGivingFlowTests {

    private static final String KEY = "PZ78-PKTEST-ABCDEF123456";

    @Autowired MockMvc mvc;
    @MockitoBean VideoMetadataClient metadata;
    @MockitoBean PayazaClient payaza;

    private String link;
    private String ada;
    private String tunde;
    private String spaceId;

    @BeforeEach
    void aChurchRoomWithALiveSession() throws Exception {
        when(metadata.fetch(any(ResolvedLink.class))).thenReturn(new VideoMetadata("Sunday Service", null, null));
        link = "https://business.payaza.africa/pay/stmarks-" + UUID.randomUUID().toString().substring(0, 8);
        ada = register("Ada Okafor");
        tunde = register("Tunde");
        spaceId = createRoom(ada);
        join(tunde, inviteOf(ada, spaceId));
        json(post("/api/v1/spaces/" + spaceId + "/sessions/watch-now"), ada, "{}").andExpect(status().isCreated());
    }

    private ResultActions registerChurch(String token, String links) throws Exception {
        return json(put("/api/v1/spaces/" + spaceId + "/give/payaza-account"), token, """
            {"publicKey":"%s","mode":"TEST","merchantName":"St Mark's Church","paymentLinks":%s}""".formatted(KEY, links));
    }

    @Test
    void aRegisteredChurchsLinkIsPaidInsideTheRoomAndConfirmedByPayaza() throws Exception {
        registerChurch(ada, "[\"" + link + "?utm_source=qr\"]").andExpect(status().isOk())
            .andExpect(jsonPath("$.publicKey").value("PZ78-PKT…3456"))
            .andExpect(jsonPath("$.mode").value("TEST"))
            .andExpect(jsonPath("$.currencyCode").value("NGN"))
            .andExpect(jsonPath("$.paymentLinks[0]").value(link));
        auth(get("/api/v1/spaces/" + spaceId + "/give/payaza-account"), ada)
            .andExpect(jsonPath("$.merchantName").value("St Mark's Church"));

        String promptId = read(json(post("/api/v1/spaces/" + spaceId + "/give/prompts"), ada, "{\"url\":\"" + link + "\"}")
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.inRoomCheckout").value(true))
            .andExpect(jsonPath("$.merchantName").value("St Mark's Church")), "$.id");
        auth(get("/api/v1/spaces/" + spaceId + "/give/prompts/current"), tunde)
            .andExpect(jsonPath("$.inRoomCheckout").value(true));

        ResultActions opened = json(post("/api/v1/spaces/" + spaceId + "/give/prompts/" + promptId + "/open"), tunde,
                "{\"anonymous\":false}")
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.checkout.merchantKey").value(KEY))
            .andExpect(jsonPath("$.checkout.connectionMode").value("Test"))
            .andExpect(jsonPath("$.checkout.currencyCode").value("NGN"))
            .andExpect(jsonPath("$.checkout.firstName").value("Tunde"));
        String giftId = read(opened, "$.giftId");
        String reference = read(opened, "$.checkout.transactionReference");
        assertThat(reference).matches("MWM[0-9A-F]{32}");

        when(payaza.status(eq(KEY), eq(PayazaAccount.Mode.TEST), eq(reference)))
            .thenReturn(new PayazaClient.Payment(PayazaClient.Outcome.COMPLETED, 500_000L, "NGN", "PZ-" + UUID.randomUUID()));
        json(post("/api/v1/gifts/" + giftId + "/result"), tunde, "{\"status\":\"COMPLETED\",\"amountMinor\":1,\"currency\":\"USD\"}")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("COMPLETED"))
            .andExpect(jsonPath("$.amountMinor").value(500_000))
            .andExpect(jsonPath("$.currency").value("NGN"))
            .andExpect(jsonPath("$.selfReported").value(false))
            .andExpect(jsonPath("$.inRoomCheckout").value(true));
        auth(get("/api/v1/spaces/" + spaceId + "/give/summary"), ada)
            .andExpect(jsonPath("$.confirmed").value(1))
            .andExpect(jsonPath("$.selfReported").value(false));
    }

    @Test
    void otherLinksAndOtherRoomsStillOpenInANewTab() throws Exception {
        registerChurch(ada, "[\"" + link + "\"]").andExpect(status().isOk());
        String charity = "https://business.payaza.africa/pay/charity-" + UUID.randomUUID().toString().substring(0, 8);
        String promptId = read(json(post("/api/v1/spaces/" + spaceId + "/give/prompts"), ada, "{\"url\":\"" + charity + "\"}")
            .andExpect(jsonPath("$.inRoomCheckout").value(false)), "$.id");
        json(post("/api/v1/spaces/" + spaceId + "/give/prompts/" + promptId + "/open"), tunde, "{}")
            .andExpect(jsonPath("$.checkout").doesNotExist())
            .andExpect(jsonPath("$.checkoutUrl").value(charity));

        // The same church link shown in someone else's room isn't theirs to take payments for
        String kemi = register("Kemi");
        String otherRoom = createRoom(kemi);
        json(post("/api/v1/spaces/" + otherRoom + "/sessions/watch-now"), kemi, "{}").andExpect(status().isCreated());
        json(post("/api/v1/spaces/" + otherRoom + "/give/prompts"), kemi, "{\"url\":\"" + link + "\"}")
            .andExpect(jsonPath("$.inRoomCheckout").value(false));

        // Removing the registration sends this room back to new tabs too
        auth(delete("/api/v1/spaces/" + spaceId + "/give/payaza-account"), ada).andExpect(status().isNoContent());
        auth(get("/api/v1/spaces/" + spaceId + "/give/payaza-account"), ada).andExpect(status().isNoContent());
        json(post("/api/v1/spaces/" + spaceId + "/give/prompts"), ada, "{\"url\":\"" + link + "\"}")
            .andExpect(jsonPath("$.inRoomCheckout").value(false));
    }

    @Test
    void onlyHostsRegisterAndOnlyRealPayazaLinksAndKeys() throws Exception {
        registerChurch(tunde, "[\"" + link + "\"]").andExpect(status().isForbidden());
        auth(get("/api/v1/spaces/" + spaceId + "/give/payaza-account"), tunde).andExpect(status().isForbidden());
        registerChurch(ada, "[\"https://paystack.com/pay/stmarks\"]").andExpect(status().isUnprocessableContent())
            .andExpect(jsonPath("$.code").value("UNSUPPORTED_PAYMENT_LINK"));
        registerChurch(ada, "[]").andExpect(status().isBadRequest());
        json(put("/api/v1/spaces/" + spaceId + "/give/payaza-account"), ada, """
            {"publicKey":"not a key!","mode":"TEST","merchantName":"X","paymentLinks":["%s"]}""".formatted(link))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        // A link the room blocked can't be registered
        json(post("/api/v1/spaces/" + spaceId + "/give/blocked-links"), ada, "{\"url\":\"" + link + "\"}").andExpect(status().isCreated());
        registerChurch(ada, "[\"" + link + "\"]").andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("LINK_BLOCKED"));
    }

    // ---- helpers -------------------------------------------------------------

    private String register(String name) throws Exception {
        return read(json(post("/api/v1/auth/register"), null, """
                {"email":"%s@example.com","password":"correct-horse-battery","displayName":"%s"}"""
                .formatted("giver-" + UUID.randomUUID(), name)).andExpect(status().isCreated()), "$.accessToken");
    }

    private String createRoom(String token) throws Exception {
        return read(json(post("/api/v1/spaces"), token, """
                {"title":"Sunday Service","audienceType":"DIASPORA","videoUrl":"https://youtu.be/dQw4w9WgXcQ"}""")
            .andExpect(status().isCreated()), "$.id");
    }

    private String inviteOf(String token, String space) throws Exception {
        return read(auth(get("/api/v1/spaces/" + space + "/invite"), token), "$.inviteToken");
    }

    private void join(String token, String invite) throws Exception {
        auth(post("/api/v1/invites/" + invite + "/join"), token).andExpect(status().isOk());
    }

    private ResultActions auth(MockHttpServletRequestBuilder request, String token) throws Exception {
        return mvc.perform(token == null ? request : request.header("Authorization", "Bearer " + token));
    }

    private ResultActions json(MockHttpServletRequestBuilder request, String token, String body) throws Exception {
        return auth(request.contentType(MediaType.APPLICATION_JSON).content(body), token);
    }

    private static String read(ResultActions result, String path) throws Exception {
        return JsonPath.read(result.andReturn().getResponse().getContentAsString(), path).toString();
    }
}
