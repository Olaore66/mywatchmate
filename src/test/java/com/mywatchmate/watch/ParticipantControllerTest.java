package com.mywatchmate.watch;

import static com.mywatchmate.web.WebLayerTestConfig.asGuest;
import static com.mywatchmate.web.WebLayerTestConfig.asUser;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mywatchmate.auth.AuthPrincipal;
import com.mywatchmate.common.ApiException;
import com.mywatchmate.watch.dto.ParticipantResponse;
import com.mywatchmate.web.WebLayerTestConfig;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ParticipantController.class)
@Import(WebLayerTestConfig.class)
class ParticipantControllerTest {

    private static final UUID USER = UUID.randomUUID();
    private static final UUID SPACE = UUID.randomUUID();
    private static final UUID TARGET = UUID.randomUUID();
    private static final String BASE = "/api/v1/spaces/" + SPACE;

    @Autowired MockMvc mvc;
    @MockitoBean MembershipService membership;

    @Test
    void guestsCanSeeWhoIsInTheirRoom() throws Exception {
        UUID guest = UUID.randomUUID();
        when(membership.list(AuthPrincipal.guest(guest, SPACE), SPACE)).thenReturn(List.of(
            new ParticipantResponse(UUID.randomUUID(), "Ada", Participant.Role.OWNER, false, Instant.now()),
            new ParticipantResponse(guest, "Bisi", Participant.Role.MEMBER, true, Instant.now())));

        mvc.perform(get(BASE + "/participants").with(asGuest(guest, SPACE)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].displayName").value("Ada"))
            .andExpect(jsonPath("$[1].guest").value(true));
    }

    @Test
    void changingARoleSendsTheNewRole() throws Exception {
        when(membership.changeRole(AuthPrincipal.user(USER), SPACE, TARGET, Participant.Role.ADMIN))
            .thenReturn(new ParticipantResponse(TARGET, "Tunde", Participant.Role.ADMIN, false, Instant.now()));

        mvc.perform(patch(BASE + "/participants/" + TARGET).with(asUser(USER))
                .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"ADMIN\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.role").value("ADMIN"));
    }

    @Test
    void roleMustBeGivenAndValid() throws Exception {
        mvc.perform(patch(BASE + "/participants/" + TARGET).with(asUser(USER))
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errors.role").exists());
        mvc.perform(patch(BASE + "/participants/" + TARGET).with(asUser(USER))
                .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"KING\"}"))
            .andExpect(status().isBadRequest());
        verifyNoInteractions(membership);
    }

    @Test
    void removingSomeoneIs204() throws Exception {
        mvc.perform(delete(BASE + "/participants/" + TARGET).with(asUser(USER))).andExpect(status().isNoContent());
        verify(membership).remove(AuthPrincipal.user(USER), SPACE, TARGET);
    }

    @Test
    void removalRefusalsKeepTheirCode() throws Exception {
        org.mockito.Mockito.doThrow(ApiException.forbidden("FORBIDDEN_ROLE", "Admins can't remove other admins."))
            .when(membership).remove(AuthPrincipal.user(USER), SPACE, TARGET);

        mvc.perform(delete(BASE + "/participants/" + TARGET).with(asUser(USER)))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("FORBIDDEN_ROLE"));
    }

    @Test
    void leavingIs204ForUsersAndGuests() throws Exception {
        UUID guest = UUID.randomUUID();
        mvc.perform(post(BASE + "/leave").with(asUser(USER))).andExpect(status().isNoContent());
        mvc.perform(post(BASE + "/leave").with(asGuest(guest, SPACE))).andExpect(status().isNoContent());

        verify(membership).leave(AuthPrincipal.user(USER), SPACE);
        verify(membership).leave(AuthPrincipal.guest(guest, SPACE), SPACE);
    }

    @Test
    void ownerLeavingIsAConflict() throws Exception {
        org.mockito.Mockito.doThrow(ApiException.conflict("OWNER_MUST_TRANSFER", "Transfer first."))
            .when(membership).leave(AuthPrincipal.user(USER), SPACE);

        mvc.perform(post(BASE + "/leave").with(asUser(USER)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("OWNER_MUST_TRANSFER"));
    }

    @Test
    void everythingNeedsSignIn() throws Exception {
        mvc.perform(get(BASE + "/participants")).andExpect(status().isUnauthorized());
        mvc.perform(post(BASE + "/leave")).andExpect(status().isUnauthorized());
        verifyNoInteractions(membership);
    }
}
