package com.mywatchmate.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import com.mywatchmate.watch.SpaceEvent;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/** The OpenAPI document the frontend generates its types from, and the events guide served next to it. */
@SpringBootTest
@AutoConfigureMockMvc
class ApiContractTests {

    @Autowired MockMvc mvc;

    private DocumentContext docs;

    @BeforeEach
    void loadDocs() throws Exception {
        docs = JsonPath.parse(mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString());
    }

    @Test
    void everyOperationHasAUniqueIdNamedAfterItsController() {
        List<String> ids = docs.read("$.paths.*.*.operationId");

        assertThat(ids).isNotEmpty().doesNotContainNull();
        assertThat(new HashSet<>(ids)).hasSameSizeAs(ids);
        assertThat(ids).noneMatch(id -> id.matches(".*_\\d+$"));
        assertThat(ids).contains("chatHistory", "accountForgot", "authLogin", "moderationDecide", "participantList");
        assertThat((String) docs.read("$.paths['/api/v1/spaces/{spaceId}/chat'].get.operationId")).isEqualTo("chatHistory");
    }

    @Test
    void responseFieldsThatAreAlwaysThereAreRequiredAndNullableOnesAreNot() {
        List<String> space = docs.read("$.components.schemas.SpaceResponse.required");
        assertThat(space).contains("id", "title", "audienceType", "owner", "playback", "chatMuted", "createdAt")
            .doesNotContain("description", "nowPlaying", "chatMutedUntil");

        List<String> participant = docs.read("$.components.schemas.ParticipantResponse.required");
        assertThat(participant).contains("id", "displayName", "role", "guest", "joinedAt", "chatMuted")
            .doesNotContain("chatMutedUntil");
        Map<String, Object> mutedUntil = docs.read("$.components.schemas.ParticipantResponse.properties.chatMutedUntil");
        assertThat(mutedUntil.toString()).contains("null");

        List<String> page = docs.read("$.components.schemas.ChatPage.required");
        assertThat(page).contains("messages").doesNotContain("olderCursor");
    }

    @Test
    void requestsKeepTheirRequiredFieldsFromValidation() {
        List<String> register = docs.read("$.components.schemas.RegisterRequest.required");
        assertThat(register).containsExactlyInAnyOrder("email", "password", "displayName");
    }

    @Test
    void accountRecoveryAndEveryEventTypeAreDocumented() {
        Map<String, Object> paths = docs.read("$.paths");
        assertThat(paths).containsKeys("/api/v1/auth/password/forgot", "/api/v1/auth/password/reset",
            "/api/v1/auth/email/verify", "/api/v1/auth/email/resend");
        String description = docs.read("$.info.description");
        for (SpaceEvent.Type type : SpaceEvent.Type.values()) {
            assertThat(description).contains(type.name());
        }
        assertThat(description).contains(DocsController.PATH).contains("server-only");
    }

    @Test
    void theEventsGuideIsPublicMarkdown() throws Exception {
        mvc.perform(get(DocsController.PATH))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Type", "text/markdown;charset=UTF-8"))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("# Real-time events")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("PLAYBACK_CHANGED")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("Closing the tab is not leaving")));
    }

    @Test
    void operationIdsAreControllerThenMethod() {
        assertThat(ApiContract.operationId("ChatController", "history")).isEqualTo("chatHistory");
        assertThat(ApiContract.operationId("GiveControllers", "show")).isEqualTo("giveShow");
        assertThat(ApiContract.operationId("LiveKitWebhookController", "receive")).isEqualTo("liveKitWebhookReceive");
    }

    @Test
    void recordsMarkOnlyNonNullableComponentsAsAlwaysPresent() {
        record Sample(String id, @io.swagger.v3.oas.annotations.media.Schema(nullable = true) String note, int count) {
        }
        assertThat(ApiContract.alwaysPresent(Sample.class)).containsExactly("id", "count");
    }
}
