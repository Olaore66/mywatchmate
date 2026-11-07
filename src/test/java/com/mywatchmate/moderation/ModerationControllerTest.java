package com.mywatchmate.moderation;

import static com.mywatchmate.web.WebLayerTestConfig.asGuest;
import static com.mywatchmate.web.WebLayerTestConfig.asUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mywatchmate.auth.AuthPrincipal;
import com.mywatchmate.common.ApiException;
import com.mywatchmate.give.PaymentLink;
import com.mywatchmate.moderation.LinkReview.Decision;
import com.mywatchmate.moderation.dto.LinkQueuePage;
import com.mywatchmate.web.WebLayerTestConfig;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ModerationController.class)
@Import(WebLayerTestConfig.class)
class ModerationControllerTest {

    private static final UUID RITA = UUID.randomUUID();
    private static final UUID LINK = UUID.randomUUID();

    @Autowired MockMvc mvc;
    @MockitoBean ModerationService moderation;

    @Test
    void signedInUsersReachTheServiceWhichChecksTheStaffRole() throws Exception {
        when(moderation.queue(AuthPrincipal.user(RITA), PaymentLink.Status.BLOCKED, "abc", 10))
            .thenReturn(new LinkQueuePage(List.of(), null, 4));

        mvc.perform(get("/api/v1/admin/links").param("status", "BLOCKED").param("cursor", "abc").param("limit", "10")
                .with(asUser(RITA)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.waiting").value(4));

        when(moderation.detail(AuthPrincipal.user(RITA), LINK)).thenThrow(ApiException.forbidden("STAFF_ONLY", "Staff only"));
        mvc.perform(get("/api/v1/admin/links/" + LINK).with(asUser(RITA)))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("STAFF_ONLY"));
    }

    @Test
    void guestsAndStrangersNeverReachIt() throws Exception {
        mvc.perform(get("/api/v1/admin/links")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/admin/links").with(asGuest(UUID.randomUUID(), UUID.randomUUID())))
            .andExpect(status().isForbidden());
        verifyNoInteractions(moderation);
    }

    @Test
    void aDecisionNeedsAllItsParts() throws Exception {
        mvc.perform(post("/api/v1/admin/links/" + LINK + "/decision").with(asUser(RITA))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"decision\":\"CONFIRM_SCAM\",\"note\":\"Wrong name\",\"expectedStatus\":\"HELD\"}"))
            .andExpect(status().isOk());
        verify(moderation).decide(AuthPrincipal.user(RITA), LINK, Decision.CONFIRM_SCAM, "Wrong name", PaymentLink.Status.HELD);

        for (String body : new String[] {
            "{\"note\":\"n\",\"expectedStatus\":\"HELD\"}",
            "{\"decision\":\"CLEAR\",\"note\":\" \",\"expectedStatus\":\"HELD\"}",
            "{\"decision\":\"CLEAR\",\"note\":\"n\"}",
            "{\"decision\":\"CLEAR\",\"note\":\"" + "x".repeat(501) + "\",\"expectedStatus\":\"HELD\"}"}) {
            mvc.perform(post("/api/v1/admin/links/" + LINK + "/decision").with(asUser(RITA))
                    .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
    }

    @Test
    void blockingByUrlNeedsAUrlAndANote() throws Exception {
        mvc.perform(post("/api/v1/admin/links/block").with(asUser(RITA)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"url\":\"https://pay.payaza.africa/give/x\",\"note\":\"Impersonation\"}"))
            .andExpect(status().isOk());
        verify(moderation).block(AuthPrincipal.user(RITA), "https://pay.payaza.africa/give/x", "Impersonation");

        mvc.perform(post("/api/v1/admin/links/block").with(asUser(RITA)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"url\":\"https://pay.payaza.africa/give/x\"}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void twoWritesAtOnceAreAConflictNotAnError() throws Exception {
        when(moderation.decide(any(), eq(LINK), any(), any(), any()))
            .thenThrow(new ObjectOptimisticLockingFailureException(PaymentLink.class, LINK));

        mvc.perform(post("/api/v1/admin/links/" + LINK + "/decision").with(asUser(RITA))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"decision\":\"CLEAR\",\"note\":\"n\",\"expectedStatus\":\"HELD\"}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("CONCURRENT_UPDATE"));
    }

    @Test
    void cursorsRoundTripAndForgeriesAreRefused() {
        ReviewCursor cursor = new ReviewCursor(java.time.Instant.parse("2026-11-02T10:15:30.123456Z"), LINK);
        assertThat(ReviewCursor.decode(cursor.encode())).isEqualTo(cursor);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> ReviewCursor.decode("!!"))
            .isInstanceOf(ApiException.class).hasMessageContaining("cursor");
    }
}
