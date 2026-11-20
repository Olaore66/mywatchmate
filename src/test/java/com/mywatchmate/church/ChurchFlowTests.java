package com.mywatchmate.church;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.mywatchmate.give.PayazaAccount;
import com.mywatchmate.give.PayazaClient;
import com.mywatchmate.watch.content.ResolvedLink;
import com.mywatchmate.watch.content.VideoMetadata;
import com.mywatchmate.watch.content.VideoMetadataClient;
import jakarta.persistence.EntityManager;
import java.util.UUID;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

/** The verified church registry over HTTP with the real database (rolled back); only Payaza is stood in for. */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ChurchFlowTests {

    private static final String KEY = "PZ78-PKLIVE-STREAMSOFJOY";
    private static final String NEW_KEY = "PZ78-PKLIVE-NEWKEY2026";

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired EntityManager entityManager;
    @MockitoBean VideoMetadataClient metadata;
    @MockitoBean PayazaClient payaza;

    private String link;
    private String churchRep;
    private String staff;

    @BeforeEach
    void setUp() throws Exception {
        when(metadata.fetch(any(ResolvedLink.class))).thenReturn(new VideoMetadata("NSPPD", null, null));
        link = "https://business.payaza.africa/pay/nsppd-" + UUID.randomUUID().toString().substring(0, 8);
        churchRep = register("Grace (Streams of Joy media)");
        staff = staffMember();
    }

    private ResultActions apply(String token, String links) throws Exception {
        return json(post("/api/v1/churches/applications"), token, """
            {"name":"Streams of Joy International","website":"https://www.streamsofjoy.org","officialEmail":"info@streamsofjoy.org",
             "publicKey":"%s","mode":"LIVE","paymentLinks":%s}""".formatted(KEY, links));
    }

    private ResultActions decide(String churchId, String decision, String expected) throws Exception {
        return json(post("/api/v1/admin/churches/" + churchId + "/decision"), staff, """
            {"decision":"%s","note":"Called the number on their website; test payment showed their name","expectedStatus":"%s"}"""
            .formatted(decision, expected));
    }

    @Test
    void aVerifiedChurchIsPaidInsideAnyRoomWithABadgeAndConfirmedByPayaza() throws Exception {
        String churchId = read(apply(churchRep, "[\"" + link + "\"]").andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("PENDING"))
            .andExpect(jsonPath("$.publicKey").value("PZ78-PKL…FJOY")), "$.id");
        auth(get("/api/v1/me/church-applications"), churchRep).andExpect(jsonPath("$[0].id").value(churchId));

        // Somebody's room watching the stream: before verification, the link opens in a new tab
        String host = register("Tunde");
        String viewer = register("Bisi Ade");
        String room = createRoom(host);
        join(viewer, inviteOf(host, room));
        json(post("/api/v1/spaces/" + room + "/sessions/watch-now"), host, "{}").andExpect(status().isCreated());
        showPrompt(host, room).andExpect(jsonPath("$.inRoomCheckout").value(false))
            .andExpect(jsonPath("$.verifiedChurch").doesNotExist());

        // Staff only; they test the key with a small payment, then approve
        auth(get("/api/v1/admin/churches"), churchRep).andExpect(status().isForbidden());
        auth(get("/api/v1/admin/churches"), staff).andExpect(status().isOk())
            .andExpect(jsonPath("$.churches[?(@.id == '" + churchId + "')]").exists())
            .andExpect(jsonPath("$.pendingApplications").isNumber());
        String reference = read(auth(post("/api/v1/admin/churches/" + churchId + "/test-checkout"), staff)
            .andExpect(jsonPath("$.merchantKey").value(KEY))
            .andExpect(jsonPath("$.connectionMode").value("Live")), "$.transactionReference");
        when(payaza.status(KEY, PayazaAccount.Mode.LIVE, reference))
            .thenReturn(new PayazaClient.Payment(PayazaClient.Outcome.COMPLETED, 10_000L, "NGN", "PZ-TEST-1"));
        auth(get("/api/v1/admin/churches/" + churchId + "/test-checkout/" + reference), staff)
            .andExpect(jsonPath("$.outcome").value("COMPLETED"))
            .andExpect(jsonPath("$.amountMinor").value(10_000));
        auth(get("/api/v1/admin/churches/" + churchId), staff)
            .andExpect(jsonPath("$.appliedBy.displayName").value("Grace (Streams of Joy media)"))
            .andExpect(jsonPath("$.checklist", hasSize(4)));

        decide(churchId, "APPROVE", "SUSPENDED").andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("CHURCH_STATUS_CHANGED"));
        decide(churchId, "APPROVE", "PENDING").andExpect(status().isOk())
            .andExpect(jsonPath("$.church.status").value("VERIFIED"))
            .andExpect(jsonPath("$.history[0].decision").value("APPROVE"));
        decide(churchId, "APPROVE", "VERIFIED").andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("DECISION_NOT_ALLOWED"));

        // Now, in that same stranger's room: paid inside the room, with the badge
        String promptId = read(showPrompt(host, room).andExpect(jsonPath("$.inRoomCheckout").value(true))
            .andExpect(jsonPath("$.verifiedChurch").value("Streams of Joy International")), "$.id");
        ResultActions opened = json(post("/api/v1/spaces/" + room + "/give/prompts/" + promptId + "/open"), viewer, "{}")
            .andExpect(jsonPath("$.checkout.merchantKey").value(KEY))
            .andExpect(jsonPath("$.verifiedChurch").value("Streams of Joy International"));
        String giftId = read(opened, "$.giftId");
        when(payaza.status(eq(KEY), eq(PayazaAccount.Mode.LIVE), eq(read(opened, "$.checkout.transactionReference"))))
            .thenReturn(new PayazaClient.Payment(PayazaClient.Outcome.COMPLETED, 2_000_000L, "NGN", "PZ-" + UUID.randomUUID()));
        json(post("/api/v1/gifts/" + giftId + "/result"), viewer, "{\"status\":\"COMPLETED\"}")
            .andExpect(jsonPath("$.status").value("COMPLETED"))
            .andExpect(jsonPath("$.amountMinor").value(2_000_000))
            .andExpect(jsonPath("$.selfReported").value(false));
        entityManager.flush(); // separate requests in real use; one rolled-back transaction here
        auth(get("/api/v1/admin/churches/" + churchId), staff).andExpect(jsonPath("$.gifts.completed").value(1));

        // No room can put its own key behind the church's link
        json(put("/api/v1/spaces/" + room + "/give/payaza-account"), host, """
            {"publicKey":"PZ78-PKLIVE-SOMEONEELSE","mode":"LIVE","merchantName":"Not them","paymentLinks":["%s"]}""".formatted(link))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("LINK_BELONGS_TO_CHURCH"));

        // Suspended: back to a new tab at once; reinstated: in the room again
        decide(churchId, "SUSPEND", "VERIFIED").andExpect(jsonPath("$.church.status").value("SUSPENDED"));
        showPrompt(host, room).andExpect(jsonPath("$.inRoomCheckout").value(false));
        decide(churchId, "REINSTATE", "SUSPENDED").andExpect(jsonPath("$.church.status").value("VERIFIED"));
        showPrompt(host, room).andExpect(jsonPath("$.inRoomCheckout").value(true));

        // The trail can't be rewritten
        assertThatThrownBy(() -> jdbc.sql("UPDATE church_reviews SET note = 'x' WHERE church_id = :id")
            .param("id", UUID.fromString(churchId)).update())
            .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
    }

    @Test
    void aNewKeyIsOnlyUsedOnceStaffApproveIt() throws Exception {
        String churchId = read(apply(churchRep, "[\"" + link + "\"]"), "$.id");
        decide(churchId, "APPROVE", "PENDING").andExpect(status().isOk());

        String stranger = register("Not the rep");
        json(post("/api/v1/churches/" + churchId + "/change-requests"), stranger, "{\"publicKey\":\"" + NEW_KEY + "\"}")
            .andExpect(status().isNotFound());
        json(post("/api/v1/churches/" + churchId + "/change-requests"), churchRep, "{}")
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("NOTHING_TO_CHANGE"));
        String changeId = read(json(post("/api/v1/churches/" + churchId + "/change-requests"), churchRep,
                "{\"publicKey\":\"" + NEW_KEY + "\"}").andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("PENDING")), "$.id");
        json(post("/api/v1/churches/" + churchId + "/change-requests"), churchRep, "{\"mode\":\"TEST\"}")
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CHANGE_PENDING"));

        // Until approved, the old key is used; staff can test the new one first
        auth(post("/api/v1/admin/churches/" + churchId + "/test-checkout"), staff).andExpect(jsonPath("$.merchantKey").value(KEY));
        auth(post("/api/v1/admin/churches/" + churchId + "/test-checkout?pendingKey=true"), staff)
            .andExpect(jsonPath("$.merchantKey").value(NEW_KEY));

        json(post("/api/v1/admin/church-changes/" + changeId + "/decision"), staff,
                "{\"decision\":\"APPROVE\",\"note\":\"New key tested with a payment\"}")
            .andExpect(jsonPath("$.church.publicKey").value("PZ78-PKL…2026"))
            .andExpect(jsonPath("$.pendingChange").doesNotExist())
            .andExpect(jsonPath("$.history[0].decision").value("APPROVE_CHANGE"));
        auth(post("/api/v1/admin/churches/" + churchId + "/test-checkout"), staff).andExpect(jsonPath("$.merchantKey").value(NEW_KEY));
    }

    @Test
    void aLinkHasOneChurchAndARejectedImpostorGivesItBack() throws Exception {
        String impostor = register("Impostor");
        String fake = read(apply(impostor, "[\"" + link + "\"]").andExpect(status().isCreated()), "$.id");
        apply(churchRep, "[\"" + link + "\"]").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("LINK_CLAIMED"));

        decide(fake, "REJECT", "PENDING").andExpect(jsonPath("$.church.status").value("REJECTED"))
            .andExpect(jsonPath("$.church.paymentLinks", hasSize(0)));
        apply(churchRep, "[\"" + link + "\"]").andExpect(status().isCreated());
    }

    @Test
    void applicationsAreCheckedAndGuestsCantApply() throws Exception {
        apply(churchRep, "[\"https://paystack.com/pay/x\"]").andExpect(status().isUnprocessableContent());
        json(post("/api/v1/churches/applications"), churchRep, """
            {"name":"X","publicKey":"bad key!","mode":"LIVE","paymentLinks":["%s"]}""".formatted(link))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        for (int i = 0; i < ChurchService.MAX_PENDING_PER_PERSON; i++) {
            apply(churchRep, "[\"https://business.payaza.africa/pay/c" + UUID.randomUUID().toString().substring(0, 8) + "\"]")
                .andExpect(status().isCreated());
        }
        apply(churchRep, "[\"" + link + "\"]").andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("TOO_MANY_APPLICATIONS"));

        String host = register("Ada");
        String room = createRoom(host);
        String guest = read(json(post("/api/v1/auth/guest"), null,
            "{\"inviteToken\":\"%s\",\"displayName\":\"Bisi\"}".formatted(inviteOf(host, room))), "$.accessToken");
        apply(guest, "[\"" + link + "\"]").andExpect(status().isForbidden());
    }

    // ---- helpers -------------------------------------------------------------

    private ResultActions showPrompt(String token, String room) throws Exception {
        return json(post("/api/v1/spaces/" + room + "/give/prompts"), token, "{\"url\":\"" + link + "\"}")
            .andExpect(status().isCreated());
    }

    private String staffMember() throws Exception {
        String email = "staff-" + UUID.randomUUID() + "@example.com";
        String token = read(json(post("/api/v1/auth/register"), null, """
                {"email":"%s","password":"correct-horse-battery","displayName":"Rita Staff"}""".formatted(email))
            .andExpect(status().isCreated()), "$.accessToken");
        entityManager.flush();
        jdbc.sql("UPDATE users SET staff_role = 'ADMIN' WHERE email = :email").param("email", email).update();
        entityManager.clear();
        return token;
    }

    private String register(String name) throws Exception {
        return read(json(post("/api/v1/auth/register"), null, """
                {"email":"%s@example.com","password":"correct-horse-battery","displayName":"%s"}"""
                .formatted("user-" + UUID.randomUUID(), name)).andExpect(status().isCreated()), "$.accessToken");
    }

    private String createRoom(String token) throws Exception {
        return read(json(post("/api/v1/spaces"), token, """
                {"title":"NSPPD with friends","audienceType":"FRIENDS","videoUrl":"https://youtu.be/dQw4w9WgXcQ"}""")
            .andExpect(status().isCreated()), "$.id");
    }

    private String inviteOf(String token, String room) throws Exception {
        return read(auth(get("/api/v1/spaces/" + room + "/invite"), token), "$.inviteToken");
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
