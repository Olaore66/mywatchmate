package com.mywatchmate.invite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.mywatchmate.notify.EmailMessage;
import com.mywatchmate.notify.EmailSender;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mail.MailSendException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

/**
 * Email invitations over HTTP against the real database (rolled back): the job is run by hand, emails
 * are captured instead of sent, and the clock is controlled.
 */
@SpringBootTest(properties = {"mywatchmate.invites.max-per-day=4", "mywatchmate.spaces.frontend-url=https://mywatchmates.com"})
@AutoConfigureMockMvc
@Transactional
class InviteFlowTests {

    @Autowired MockMvc mvc;
    @Autowired InviteDispatchJob dispatch;
    @MockitoBean Clock clock;
    @MockitoBean EmailSender email;

    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.now().truncatedTo(ChronoUnit.SECONDS));
    private final List<EmailMessage> outbox = new ArrayList<>();

    @BeforeEach
    void controlledWorld() {
        when(clock.instant()).thenAnswer(i -> now.get());
        doAnswer(i -> outbox.add(i.getArgument(0))).when(email).send(any(EmailMessage.class));
    }

    private void advance(Duration d) {
        now.set(now.get().plus(d));
    }

    @Test
    void invitedByEmailThenJoined() throws Exception {
        String ada = register("Ada", "ada-" + UUID.randomUUID() + "@example.com");
        String spaceId = createRoom(ada);
        String kemiEmail = "kemi-" + UUID.randomUUID() + "@example.com";

        // Ada invites Kemi (twice, in different case) and a typo; it returns at once
        MvcResult invited = mvc.perform(json(post("/api/v1/spaces/" + spaceId + "/invites/email"), ada, """
                {"emails":["%s","%s","not-an-email"],"customMessage":"Join us! https://evil.example/x"}"""
                .formatted(kemiEmail.toUpperCase(), kemiEmail)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.queued").value(1))
            .andExpect(jsonPath("$.results.length()").value(2))
            .andExpect(jsonPath("$.results[0].status").value("QUEUED"))
            .andExpect(jsonPath("$.results[1].status").value("INVALID_EMAIL"))
            .andReturn();
        String invitationId = read(invited, "$.results[0].invitationId");
        assertThat(outbox).as("nothing sent during the request").isEmpty();

        // The job emails the room's link, with the note but not the link in it
        assertThat(dispatch.run()).isEqualTo(1);
        EmailMessage sent = outbox.getFirst();
        String inviteToken = read(mvc.perform(auth(get("/api/v1/spaces/" + spaceId + "/invite"), ada)).andReturn(), "$.inviteToken");
        assertThat(sent.to()).isEqualTo(kemiEmail);
        assertThat(sent.subject()).isEqualTo("Ada invited you to watch together in \"Sunday Service\"");
        assertThat(sent.text()).contains("https://mywatchmates.com/join/" + inviteToken)
            .contains("Join us! [link removed]").doesNotContain("evil.example");
        assertThat(sent.html()).contains("Join the room");
        assertThat(dispatch.run()).as("sent once").isZero();

        mvc.perform(auth(get("/api/v1/spaces/" + spaceId + "/invites/email"), ada))
            .andExpect(jsonPath("$[0].status").value("SENT"))
            .andExpect(jsonPath("$[0].invitedBy.displayName").value("Ada"));
        mvc.perform(json(post("/api/v1/spaces/" + spaceId + "/invites/email"), ada, "{\"emails\":[\"%s\"]}".formatted(kemiEmail)))
            .andExpect(jsonPath("$.results[0].status").value("ALREADY_INVITED"))
            .andExpect(jsonPath("$.results[0].invitationId").value(invitationId));

        // Kemi signs up with that address and joins through the link: her invitation is done
        String kemi = register("Kemi", kemiEmail);
        mvc.perform(auth(post("/api/v1/invites/" + inviteToken + "/join"), kemi)).andExpect(status().isOk());
        mvc.perform(auth(get("/api/v1/spaces/" + spaceId + "/invites/email"), ada))
            .andExpect(jsonPath("$[0].status").value("JOINED"))
            .andExpect(jsonPath("$[0].joinedAt").exists());
        mvc.perform(json(post("/api/v1/spaces/" + spaceId + "/invites/email"), ada, "{\"emails\":[\"%s\"]}".formatted(kemiEmail)))
            .andExpect(jsonPath("$.results[0].status").value("ALREADY_MEMBER"));

        // Members can't see or send invitations
        mvc.perform(auth(get("/api/v1/spaces/" + spaceId + "/invites/email"), kemi)).andExpect(status().isForbidden());
    }

    @Test
    void failedSendsRetryAndResendCancelAndTheDailyLimit() throws Exception {
        String ada = register("Ada", "ada-" + UUID.randomUUID() + "@example.com");
        String spaceId = createRoom(ada);

        // The mail server refuses: retried, then FAILED after 3 attempts
        doAnswer(i -> { throw new MailSendException("Mailbox unavailable"); })
            .when(email).send(any(EmailMessage.class));
        String id = read(mvc.perform(json(post("/api/v1/spaces/" + spaceId + "/invites/email"), ada,
            "{\"emails\":[\"bounce@example.com\"]}")).andReturn(), "$.results[0].invitationId");
        dispatch.run();
        advance(Duration.ofMinutes(1));
        dispatch.run();
        advance(Duration.ofMinutes(2));
        dispatch.run();
        mvc.perform(auth(get("/api/v1/spaces/" + spaceId + "/invites/email"), ada))
            .andExpect(jsonPath("$[0].status").value("FAILED"))
            .andExpect(jsonPath("$[0].lastError").value("Mailbox unavailable"));

        // Resend after fixing things; too soon is refused; then cancel
        doAnswer(i -> outbox.add(i.getArgument(0))).when(email).send(any(EmailMessage.class));
        advance(Duration.ofMinutes(10));
        mvc.perform(auth(post("/api/v1/spaces/" + spaceId + "/invites/email/" + id + "/resend"), ada))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("PENDING"));
        mvc.perform(auth(post("/api/v1/spaces/" + spaceId + "/invites/email/" + id + "/resend"), ada))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("RESEND_TOO_SOON"));
        mvc.perform(auth(delete("/api/v1/spaces/" + spaceId + "/invites/email/" + id), ada)).andExpect(status().isNoContent());
        assertThat(dispatch.run()).as("cancelled before it went").isZero();

        // The room's daily limit is 4 here (the first invitation and its resend used 1)
        mvc.perform(json(post("/api/v1/spaces/" + spaceId + "/invites/email"), ada,
                "{\"emails\":[\"a@example.com\",\"b@example.com\",\"c@example.com\",\"d@example.com\"]}"))
            .andExpect(jsonPath("$.queued").value(3))
            .andExpect(jsonPath("$.results[3].status").value("RATE_LIMITED"));
    }

    // ---- helpers -------------------------------------------------------------

    private String register(String name, String address) throws Exception {
        return read(mvc.perform(json(post("/api/v1/auth/register"), null, """
                {"email":"%s","password":"correct-horse-battery","displayName":"%s"}""".formatted(address, name)))
            .andExpect(status().isCreated()).andReturn(), "$.accessToken");
    }

    private String createRoom(String token) throws Exception {
        return read(mvc.perform(json(post("/api/v1/spaces"), token, """
                {"title":"Sunday Service","audienceType":"DIASPORA"}"""))
            .andExpect(status().isCreated()).andReturn(), "$.id");
    }

    private static MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder request, String token) {
        return token == null ? request : request.header("Authorization", "Bearer " + token);
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String token, String body) {
        return auth(request, token).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static String read(MvcResult result, String path) throws Exception {
        return JsonPath.read(result.getResponse().getContentAsString(), path).toString();
    }
}
