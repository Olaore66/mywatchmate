package com.mywatchmate.connect;

import static com.mywatchmate.web.WebLayerTestConfig.asGuest;
import static com.mywatchmate.web.WebLayerTestConfig.asUser;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
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
import com.mywatchmate.connect.dto.ChatMessageResponse;
import com.mywatchmate.connect.dto.ChatPage;
import com.mywatchmate.connect.dto.MemberMuted;
import com.mywatchmate.watch.Participant;
import com.mywatchmate.watch.dto.ParticipantResponse;
import com.mywatchmate.watch.dto.PersonRef;
import com.mywatchmate.web.WebLayerTestConfig;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ChatController.class)
@Import(WebLayerTestConfig.class)
class ChatControllerTest {

    private static final UUID USER = UUID.randomUUID();
    private static final UUID SPACE = UUID.randomUUID();
    private static final Instant T0 = Instant.parse("2026-11-02T05:00:00Z");

    @Autowired MockMvc mvc;
    @MockitoBean ChatService chat;

    private static ChatMessageResponse message(String body) {
        return new ChatMessageResponse(UUID.randomUUID(), SPACE, null, new PersonRef(UUID.randomUUID(), "Bisi"), true,
            body, false, false, T0);
    }

    @Test
    void historyPassesTheCursorAndLimit() throws Exception {
        when(chat.history(AuthPrincipal.user(USER), SPACE, "abc", 20))
            .thenReturn(new ChatPage(List.of(message("Amen")), "older"));

        mvc.perform(get("/api/v1/spaces/" + SPACE + "/chat").param("before", "abc").param("limit", "20").with(asUser(USER)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.messages[0].body").value("Amen"))
            .andExpect(jsonPath("$.messages[0].sender.displayName").value("Bisi"))
            .andExpect(jsonPath("$.messages[0].senderGuest").value(true))
            .andExpect(jsonPath("$.olderCursor").value("older"));
    }

    @Test
    void guestsChatToo() throws Exception {
        UUID bisi = UUID.randomUUID();
        when(chat.send(AuthPrincipal.guest(bisi, SPACE), SPACE, "Amen 🙏")).thenReturn(message("Amen 🙏"));

        mvc.perform(post("/api/v1/spaces/" + SPACE + "/chat").with(asGuest(bisi, SPACE))
                .contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"Amen 🙏\"}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.body").value("Amen 🙏"));
    }

    @Test
    void blankOrTooLongMessagesAreRejectedBeforeTheService() throws Exception {
        mvc.perform(post("/api/v1/spaces/" + SPACE + "/chat").with(asUser(USER))
                .contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"   \"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errors.body").exists());
        mvc.perform(post("/api/v1/spaces/" + SPACE + "/chat").with(asUser(USER))
                .contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"" + "x".repeat(2001) + "\"}"))
            .andExpect(status().isBadRequest());
        verifyNoInteractions(chat);
    }

    @Test
    void mutedAndTooFastKeepTheirCodes() throws Exception {
        when(chat.send(any(), eq(SPACE), any()))
            .thenThrow(ApiException.forbidden("CHAT_MUTED", "A host has muted you in this chat."))
            .thenThrow(new ApiException(HttpStatus.TOO_MANY_REQUESTS, "CHAT_RATE_LIMITED", "Too fast"));

        mvc.perform(post("/api/v1/spaces/" + SPACE + "/chat").with(asUser(USER))
                .contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"hi\"}"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("CHAT_MUTED"));
        mvc.perform(post("/api/v1/spaces/" + SPACE + "/chat").with(asUser(USER))
                .contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"hi\"}"))
            .andExpect(status().isTooManyRequests())
            .andExpect(jsonPath("$.code").value("CHAT_RATE_LIMITED"));
    }

    @Test
    void deletingAndMarkingRead() throws Exception {
        UUID messageId = UUID.randomUUID();

        mvc.perform(delete("/api/v1/spaces/" + SPACE + "/chat/" + messageId).with(asUser(USER)))
            .andExpect(status().isNoContent());
        mvc.perform(post("/api/v1/spaces/" + SPACE + "/chat/read").with(asUser(USER)))
            .andExpect(status().isNoContent());

        verify(chat).delete(AuthPrincipal.user(USER), SPACE, messageId);
        verify(chat).markRead(AuthPrincipal.user(USER), SPACE);
    }

    @Test
    void muteForAWhileOrUntilUnmuted() throws Exception {
        UUID tunde = UUID.randomUUID();
        MemberMuted muted = new MemberMuted(
            new ParticipantResponse(tunde, "Tunde", Participant.Role.MEMBER, false, T0), T0.plusSeconds(3600));
        when(chat.mute(AuthPrincipal.user(USER), SPACE, tunde, 60)).thenReturn(muted);
        when(chat.mute(AuthPrincipal.user(USER), SPACE, tunde, null)).thenReturn(new MemberMuted(muted.participant(), null));

        mvc.perform(post("/api/v1/spaces/" + SPACE + "/participants/" + tunde + "/mute").with(asUser(USER))
                .contentType(MediaType.APPLICATION_JSON).content("{\"minutes\":60}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.participant.displayName").value("Tunde"))
            .andExpect(jsonPath("$.mutedUntil").value("2026-11-02T06:00:00Z"));
        mvc.perform(post("/api/v1/spaces/" + SPACE + "/participants/" + tunde + "/mute").with(asUser(USER)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.mutedUntil").doesNotExist());
        mvc.perform(delete("/api/v1/spaces/" + SPACE + "/participants/" + tunde + "/mute").with(asUser(USER)))
            .andExpect(status().isNoContent());
        verify(chat).unmute(AuthPrincipal.user(USER), SPACE, tunde);
    }

    @Test
    void muteLengthIsChecked() throws Exception {
        UUID tunde = UUID.randomUUID();
        for (String minutes : new String[] {"0", "-5", "43201"}) {
            mvc.perform(post("/api/v1/spaces/" + SPACE + "/participants/" + tunde + "/mute").with(asUser(USER))
                    .contentType(MediaType.APPLICATION_JSON).content("{\"minutes\":" + minutes + "}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.minutes").exists());
        }
        verifyNoInteractions(chat);
    }

    @Test
    void historyDefaultsAndSignIn() throws Exception {
        when(chat.history(eq(AuthPrincipal.user(USER)), eq(SPACE), isNull(), isNull())).thenReturn(new ChatPage(List.of(), null));

        mvc.perform(get("/api/v1/spaces/" + SPACE + "/chat").with(asUser(USER)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.messages").isEmpty())
            .andExpect(jsonPath("$.olderCursor").doesNotExist());
        mvc.perform(get("/api/v1/spaces/" + SPACE + "/chat")).andExpect(status().isUnauthorized());
    }
}
