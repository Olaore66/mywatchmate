package com.mywatchmate.watch;

import static com.mywatchmate.web.WebLayerTestConfig.asGuest;
import static com.mywatchmate.web.WebLayerTestConfig.asUser;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mywatchmate.common.ApiException;
import com.mywatchmate.watch.dto.InvitePreviewResponse;
import com.mywatchmate.watch.dto.PersonRef;
import com.mywatchmate.watch.dto.SpaceResponse;
import com.mywatchmate.web.WebLayerTestConfig;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(InviteController.class)
@Import(WebLayerTestConfig.class)
class InviteControllerTest {

    private static final UUID USER = UUID.randomUUID();

    @Autowired MockMvc mvc;
    @MockitoBean MembershipService membership;

    @Test
    void previewWorksWithoutSigningIn() throws Exception {
        when(membership.preview("k7Pq2rXvB9mT4wZa")).thenReturn(new InvitePreviewResponse("6am Prayer", null,
            Space.AudienceType.DIASPORA, "Ada", 3, false, true, "Morning Prayer", null));

        mvc.perform(get("/api/v1/invites/k7Pq2rXvB9mT4wZa"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.spaceTitle").value("6am Prayer"))
            .andExpect(jsonPath("$.ownerName").value("Ada"))
            .andExpect(jsonPath("$.allowGuests").value(true));
    }

    @Test
    void deadLinkIs404() throws Exception {
        when(membership.preview("old")).thenThrow(ApiException.notFound("INVITE_NOT_FOUND", "gone"));

        mvc.perform(get("/api/v1/invites/old"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("INVITE_NOT_FOUND"));
    }

    @Test
    void signedInUsersJoin() throws Exception {
        UUID space = UUID.randomUUID();
        when(membership.join(USER, "tok")).thenReturn(new SpaceResponse(space, "6am Prayer", null,
            Space.AudienceType.DIASPORA, true, false, false, new PersonRef(UUID.randomUUID(), "Ada"), "MEMBER",
            UUID.randomUUID(), false, false, 2, 50, null, null, 0, 0, 0, null, false, null, Instant.now()));

        mvc.perform(post("/api/v1/invites/tok/join").with(asUser(USER)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.myRole").value("MEMBER"));
    }

    @Test
    void joiningNeedsAnAccountGuestsUseAuthGuestInstead() throws Exception {
        mvc.perform(post("/api/v1/invites/tok/join")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/invites/tok/join").with(asGuest(UUID.randomUUID(), UUID.randomUUID())))
            .andExpect(status().isForbidden());
        verifyNoInteractions(membership);
    }

    @Test
    void fullRoomIsAConflict() throws Exception {
        when(membership.join(USER, "tok")).thenThrow(ApiException.conflict("SPACE_FULL", "This room is full."));

        mvc.perform(post("/api/v1/invites/tok/join").with(asUser(USER)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("SPACE_FULL"));
    }
}
