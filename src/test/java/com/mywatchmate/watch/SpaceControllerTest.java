package com.mywatchmate.watch;

import static com.mywatchmate.web.WebLayerTestConfig.asGuest;
import static com.mywatchmate.web.WebLayerTestConfig.asUser;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mywatchmate.auth.AuthPrincipal;
import com.mywatchmate.common.ApiException;
import com.mywatchmate.watch.dto.InviteResponse;
import com.mywatchmate.watch.dto.PersonRef;
import com.mywatchmate.watch.dto.SpaceResponse;
import com.mywatchmate.watch.dto.SpaceSummaryResponse;
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

@WebMvcTest(SpaceController.class)
@Import(WebLayerTestConfig.class)
class SpaceControllerTest {

    private static final UUID USER = UUID.randomUUID();
    private static final UUID SPACE = UUID.randomUUID();

    @Autowired MockMvc mvc;
    @MockitoBean SpaceService spaces;
    @MockitoBean MembershipService membership;

    @Test
    void createReturns201WithTheRoom() throws Exception {
        when(spaces.create(eq(USER), any())).thenReturn(room("OWNER"));

        mvc.perform(post("/api/v1/spaces").with(asUser(USER)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"6am Prayer\",\"audienceType\":\"DIASPORA\",\"videoUrl\":\"https://youtu.be/dQw4w9WgXcQ\"}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.myRole").value("OWNER"))
            .andExpect(jsonPath("$.title").value("6am Prayer"));
        verify(spaces).create(eq(USER), argThat(r -> r.title().equals("6am Prayer")
            && r.audienceType() == Space.AudienceType.DIASPORA && r.videoUrl().equals("https://youtu.be/dQw4w9WgXcQ")));
    }

    @Test
    void createValidatesTheBodyBeforeCallingTheService() throws Exception {
        mvc.perform(post("/api/v1/spaces").with(asUser(USER)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"  \",\"audienceType\":null}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
            .andExpect(jsonPath("$.errors.title").exists())
            .andExpect(jsonPath("$.errors.audienceType").exists());
        verifyNoInteractions(spaces);
    }

    @Test
    void unknownAudienceTypeIsABadRequest() throws Exception {
        mvc.perform(post("/api/v1/spaces").with(asUser(USER)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Room\",\"audienceType\":\"ALIENS\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
        verifyNoInteractions(spaces);
    }

    @Test
    void creatingAndListingNeedARegisteredUser() throws Exception {
        mvc.perform(post("/api/v1/spaces").contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Room\",\"audienceType\":\"OTHER\"}"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        mvc.perform(post("/api/v1/spaces").with(asGuest(UUID.randomUUID(), SPACE)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Room\",\"audienceType\":\"OTHER\"}"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(get("/api/v1/spaces").with(asGuest(UUID.randomUUID(), SPACE))).andExpect(status().isForbidden());
        verifyNoInteractions(spaces);
    }

    @Test
    void myRoomsDefaultsToActiveRooms() throws Exception {
        when(spaces.listMine(USER, false)).thenReturn(List.of(new SpaceSummaryResponse(SPACE, "6am Prayer",
            Space.AudienceType.DIASPORA, Participant.Role.OWNER, 3L, "Morning Prayer", null, 4L, null, Instant.now())));

        mvc.perform(get("/api/v1/spaces").with(asUser(USER)))
            .andExpect(jsonPath("$[0].unreadCount").value(4))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].memberCount").value(3))
            .andExpect(jsonPath("$[0].nowPlayingTitle").value("Morning Prayer"));

        mvc.perform(get("/api/v1/spaces?archived=true").with(asUser(USER))).andExpect(status().isOk());
        verify(spaces).listMine(USER, true);
    }

    @Test
    void guestsCanOpenTheirRoom() throws Exception {
        UUID guest = UUID.randomUUID();
        when(spaces.get(AuthPrincipal.guest(guest, SPACE), SPACE)).thenReturn(room("GUEST"));

        mvc.perform(get("/api/v1/spaces/" + SPACE).with(asGuest(guest, SPACE)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.myRole").value("GUEST"));
    }

    @Test
    void serviceErrorsKeepTheirStatusAndCode() throws Exception {
        when(spaces.get(AuthPrincipal.user(USER), SPACE)).thenThrow(SpaceAccess.notFound());

        mvc.perform(get("/api/v1/spaces/" + SPACE).with(asUser(USER)))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("SPACE_NOT_FOUND"));
    }

    @Test
    void malformedRoomIdIsABadRequest() throws Exception {
        mvc.perform(get("/api/v1/spaces/not-a-uuid").with(asUser(USER))).andExpect(status().isBadRequest());
        verifyNoInteractions(spaces);
    }

    @Test
    void updateRejectsABlankTitleButAcceptsPartialBodies() throws Exception {
        mvc.perform(patch("/api/v1/spaces/" + SPACE).with(asUser(USER)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"   \"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errors.title").value("must not be blank"));

        when(spaces.update(eq(AuthPrincipal.user(USER)), eq(SPACE), any())).thenReturn(room("ADMIN"));
        mvc.perform(patch("/api/v1/spaces/" + SPACE).with(asUser(USER)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"allowGuests\":false}"))
            .andExpect(status().isOk());
        verify(spaces).update(eq(AuthPrincipal.user(USER)), eq(SPACE),
            argThat(r -> r.title() == null && Boolean.FALSE.equals(r.allowGuests())));
    }

    @Test
    void archiveRestoreInviteAndRotateGoToTheService() throws Exception {
        AuthPrincipal ada = AuthPrincipal.user(USER);
        InviteResponse invite = new InviteResponse("tok", "http://localhost:3000/join/tok");
        when(spaces.archive(ada, SPACE)).thenReturn(room("OWNER"));
        when(spaces.restore(ada, SPACE)).thenReturn(room("OWNER"));
        when(spaces.invite(ada, SPACE)).thenReturn(invite);
        when(spaces.rotateInvite(ada, SPACE)).thenReturn(invite);

        mvc.perform(post("/api/v1/spaces/" + SPACE + "/archive").with(asUser(USER))).andExpect(status().isOk());
        mvc.perform(post("/api/v1/spaces/" + SPACE + "/restore").with(asUser(USER))).andExpect(status().isOk());
        mvc.perform(get("/api/v1/spaces/" + SPACE + "/invite").with(asUser(USER)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.inviteUrl").value("http://localhost:3000/join/tok"));
        mvc.perform(post("/api/v1/spaces/" + SPACE + "/invite/rotate").with(asUser(USER))).andExpect(status().isOk());

        verify(spaces).archive(ada, SPACE);
        verify(spaces).restore(ada, SPACE);
        verify(spaces).rotateInvite(ada, SPACE);
    }

    @Test
    void transferNeedsAParticipantId() throws Exception {
        mvc.perform(post("/api/v1/spaces/" + SPACE + "/transfer-ownership").with(asUser(USER))
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errors.participantId").exists());

        UUID target = UUID.randomUUID();
        when(membership.transferOwnership(AuthPrincipal.user(USER), SPACE, target)).thenReturn(room("ADMIN"));
        mvc.perform(post("/api/v1/spaces/" + SPACE + "/transfer-ownership").with(asUser(USER))
                .contentType(MediaType.APPLICATION_JSON).content("{\"participantId\":\"" + target + "\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.myRole").value("ADMIN"));
    }

    @Test
    void forbiddenFromTheServiceIs403() throws Exception {
        when(spaces.archive(AuthPrincipal.user(USER), SPACE))
            .thenThrow(ApiException.forbidden("FORBIDDEN_ROLE", "Only the room owner can do this."));

        mvc.perform(post("/api/v1/spaces/" + SPACE + "/archive").with(asUser(USER)))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("FORBIDDEN_ROLE"))
            .andExpect(jsonPath("$.detail").value("Only the room owner can do this."));
    }

    private static SpaceResponse room(String myRole) {
        boolean host = myRole.equals("OWNER");
        return new SpaceResponse(SPACE, "6am Prayer", null, Space.AudienceType.DIASPORA, true, false, false,
            new PersonRef(USER, "Ada"), myRole, UUID.randomUUID(), host, host, 1, 50, null, null, 0, 3, 2, null, false, null, Instant.now());
    }
}
