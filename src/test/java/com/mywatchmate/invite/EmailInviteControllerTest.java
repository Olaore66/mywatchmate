package com.mywatchmate.invite;

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
import com.mywatchmate.invite.dto.EmailInviteResponse;
import com.mywatchmate.invite.dto.EmailInviteResult;
import com.mywatchmate.invite.dto.EmailInviteResult.Outcome;
import com.mywatchmate.invite.dto.InvitationResponse;
import com.mywatchmate.web.WebLayerTestConfig;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(EmailInviteController.class)
@Import(WebLayerTestConfig.class)
class EmailInviteControllerTest {

    private static final UUID USER = UUID.randomUUID();
    private static final UUID SPACE = UUID.randomUUID();
    private static final Instant T0 = Instant.parse("2026-11-02T05:00:00Z");

    @Autowired MockMvc mvc;
    @MockitoBean EmailInviteService invites;

    private static String emails(int n) {
        return IntStream.range(0, n).mapToObj(i -> "\"p" + i + "@example.com\"").collect(Collectors.joining(","));
    }

    @Test
    void invitingReturnsAResultPerAddress() throws Exception {
        UUID queued = UUID.randomUUID();
        when(invites.invite(eq(AuthPrincipal.user(USER)), eq(SPACE), any())).thenReturn(new EmailInviteResponse(List.of(
            new EmailInviteResult("friend@example.com", Outcome.QUEUED, queued),
            new EmailInviteResult("nope", Outcome.INVALID_EMAIL, null)), 1));

        mvc.perform(post("/api/v1/spaces/" + SPACE + "/invites/email").with(asUser(USER))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"emails\":[\"friend@example.com\",\"nope\"],\"customMessage\":\"Join us\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.queued").value(1))
            .andExpect(jsonPath("$.results[0].status").value("QUEUED"))
            .andExpect(jsonPath("$.results[0].invitationId").value(queued.toString()))
            .andExpect(jsonPath("$.results[1].status").value("INVALID_EMAIL"));
        verify(invites).invite(eq(AuthPrincipal.user(USER)), eq(SPACE),
            argThat(r -> r.emails().size() == 2 && "Join us".equals(r.customMessage())));
    }

    @Test
    void tooManyNoneOrALongNoteAreRejectedBeforeTheService() throws Exception {
        for (String body : new String[] {"{\"emails\":[]}", "{}", "{\"emails\":[" + emails(21) + "]}",
            "{\"emails\":[\"a@example.com\"],\"customMessage\":\"" + "x".repeat(501) + "\"}",
            "{\"emails\":[null]}"}) {
            mvc.perform(post("/api/v1/spaces/" + SPACE + "/invites/email").with(asUser(USER))
                    .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(invites);
    }

    @Test
    void twentyIsFine() throws Exception {
        when(invites.invite(any(), eq(SPACE), any())).thenReturn(new EmailInviteResponse(List.of(), 0));

        mvc.perform(post("/api/v1/spaces/" + SPACE + "/invites/email").with(asUser(USER))
                .contentType(MediaType.APPLICATION_JSON).content("{\"emails\":[" + emails(20) + "]}"))
            .andExpect(status().isOk());
    }

    @Test
    void listResendAndCancel() throws Exception {
        UUID id = UUID.randomUUID();
        InvitationResponse pending = new InvitationResponse(id, "friend@example.com", SpaceInvitation.Status.PENDING,
            null, T0, null, null, T0.plusSeconds(86400 * 14), null);
        when(invites.list(AuthPrincipal.user(USER), SPACE)).thenReturn(List.of(pending));
        when(invites.resend(AuthPrincipal.user(USER), SPACE, id)).thenReturn(pending);

        mvc.perform(get("/api/v1/spaces/" + SPACE + "/invites/email").with(asUser(USER)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].email").value("friend@example.com"))
            .andExpect(jsonPath("$[0].status").value("PENDING"));
        mvc.perform(post("/api/v1/spaces/" + SPACE + "/invites/email/" + id + "/resend").with(asUser(USER)))
            .andExpect(status().isOk());
        mvc.perform(delete("/api/v1/spaces/" + SPACE + "/invites/email/" + id).with(asUser(USER)))
            .andExpect(status().isNoContent());
        verify(invites).cancel(AuthPrincipal.user(USER), SPACE, id);
    }

    @Test
    void refusalsKeepTheirCodes() throws Exception {
        UUID id = UUID.randomUUID();
        when(invites.resend(any(), eq(SPACE), eq(id)))
            .thenThrow(ApiException.conflict("RESEND_TOO_SOON", "Try again in a few minutes."))
            .thenThrow(new ApiException(HttpStatus.TOO_MANY_REQUESTS, "INVITE_LIMIT_REACHED", "Tomorrow."));
        when(invites.list(any(), eq(SPACE))).thenThrow(ApiException.forbidden("FORBIDDEN_ROLE", "Hosts only."));

        mvc.perform(post("/api/v1/spaces/" + SPACE + "/invites/email/" + id + "/resend").with(asUser(USER)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("RESEND_TOO_SOON"));
        mvc.perform(post("/api/v1/spaces/" + SPACE + "/invites/email/" + id + "/resend").with(asUser(USER)))
            .andExpect(status().isTooManyRequests())
            .andExpect(jsonPath("$.code").value("INVITE_LIMIT_REACHED"));
        mvc.perform(get("/api/v1/spaces/" + SPACE + "/invites/email").with(asUser(USER)))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("FORBIDDEN_ROLE"));
    }

    @Test
    void needsSignIn() throws Exception {
        mvc.perform(post("/api/v1/spaces/" + SPACE + "/invites/email").contentType(MediaType.APPLICATION_JSON)
                .content("{\"emails\":[\"a@example.com\"]}"))
            .andExpect(status().isUnauthorized());
    }
}
