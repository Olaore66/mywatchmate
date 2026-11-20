package com.mywatchmate.auth;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.mywatchmate.auth.GoogleTokenVerifier.GoogleAccount;
import com.mywatchmate.common.ApiException;
import java.util.UUID;
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

/** Google sign-in over HTTP with the real database (rolled back); only Google's own check is stood in for. */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class GoogleSignInFlowTests {

    private static final String PASSWORD = "correct-horse-battery";

    @Autowired MockMvc mvc;
    @MockitoBean GoogleTokenVerifier google;

    private String googleUser(String token, String subject, String email, String name) {
        when(google.verify(token)).thenReturn(new GoogleAccount(subject, email, name));
        return token;
    }

    @Test
    void signingUpAndBackInWithGoogleIsTheSameAccount() throws Exception {
        String email = "g-" + UUID.randomUUID() + "@gmail.com";
        String token = googleUser("t1", "sub-" + UUID.randomUUID(), email, "Ada Okafor");

        String first = read(signIn(token).andExpect(status().isOk())
            .andExpect(jsonPath("$.user.email").value(email))
            .andExpect(jsonPath("$.user.displayName").value("Ada Okafor"))
            .andExpect(jsonPath("$.user.emailVerified").value(true))
            .andExpect(jsonPath("$.user.hasPassword").value(false))
            .andExpect(jsonPath("$.user.timezone").value("Europe/London")), "$.user.id");

        String access = read(signIn(token).andExpect(status().isOk()).andExpect(jsonPath("$.user.id").value(first)), "$.accessToken");
        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + access))
            .andExpect(jsonPath("$.id").value(first));
        // No password, so password sign-in can't get in
        call("/api/v1/auth/login", "{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, PASSWORD))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void anExistingAccountWithThatEmailGetsGoogleLinkedToIt() throws Exception {
        String email = "linked-" + UUID.randomUUID() + "@gmail.com";
        String registered = read(call("/api/v1/auth/register", """
                {"email":"%s","password":"%s","displayName":"Ada"}""".formatted(email, PASSWORD))
            .andExpect(status().isCreated()), "$.user.id");

        signIn(googleUser("t2", "sub-" + UUID.randomUUID(), email.toUpperCase(), "Ada O"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.user.id").value(registered))
            .andExpect(jsonPath("$.user.displayName").value("Ada"))
            .andExpect(jsonPath("$.user.emailVerified").value(true));
    }

    @Test
    void aGuestBecomesAGoogleAccountAndKeepsTheirRoom() throws Exception {
        String owner = read(call("/api/v1/auth/register", """
                {"email":"owner-%s@example.com","password":"%s","displayName":"Owner"}""".formatted(UUID.randomUUID(), PASSWORD))
            .andExpect(status().isCreated()), "$.accessToken");
        String spaceId = read(authed(post("/api/v1/spaces"), owner, "{\"title\":\"Prayer\",\"audienceType\":\"DIASPORA\"}")
            .andExpect(status().isCreated()), "$.id");
        String invite = read(mvc.perform(get("/api/v1/spaces/" + spaceId + "/invite").header("Authorization", "Bearer " + owner)),
            "$.inviteToken");
        String guest = read(call("/api/v1/auth/guest", "{\"inviteToken\":\"%s\",\"displayName\":\"Bisi\"}".formatted(invite))
            .andExpect(status().isCreated()), "$.accessToken");

        String email = "bisi-" + UUID.randomUUID() + "@gmail.com";
        String access = read(authed(post("/api/v1/auth/upgrade/google"), guest,
                "{\"idToken\":\"%s\"}".formatted(googleUser("t3", "sub-" + UUID.randomUUID(), email, "Bisi Ade")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.user.email").value(email)), "$.accessToken");

        mvc.perform(get("/api/v1/spaces").header("Authorization", "Bearer " + access))
            .andExpect(jsonPath("$[0].id").value(spaceId));
        // Signed-in users use /auth/google, not the guest upgrade
        authed(post("/api/v1/auth/upgrade/google"), access, "{\"idToken\":\"t3\"}").andExpect(status().isForbidden());
    }

    @Test
    void googlesRefusalsComeBackWithTheirCodes() throws Exception {
        when(google.verify("forged")).thenThrow(ApiException.unauthorized("INVALID_GOOGLE_TOKEN", "no"));

        call("/api/v1/auth/google", "{\"idToken\":\"forged\"}")
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("INVALID_GOOGLE_TOKEN"));
        call("/api/v1/auth/google", "{\"idToken\":\" \"}")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    // ---- helpers -------------------------------------------------------------

    private ResultActions signIn(String token) throws Exception {
        return call("/api/v1/auth/google", "{\"idToken\":\"%s\",\"timezone\":\"Europe/London\"}".formatted(token));
    }

    private ResultActions call(String path, String body) throws Exception {
        return mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions authed(MockHttpServletRequestBuilder request, String token, String body) throws Exception {
        return mvc.perform(request.header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private static String read(ResultActions result, String path) throws Exception {
        return JsonPath.read(result.andReturn().getResponse().getContentAsString(), path).toString();
    }
}
