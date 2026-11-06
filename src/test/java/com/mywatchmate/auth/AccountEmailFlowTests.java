package com.mywatchmate.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.mywatchmate.notify.EmailMessage;
import com.mywatchmate.notify.EmailSender;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Email confirmation and password reset with the whole app and database; emails are captured, not sent. Not
 * rolled back: emails only go out after commit, as in production (rows are left with random emails).
 */
@SpringBootTest
@AutoConfigureMockMvc
class AccountEmailFlowTests {

    private static final String PASSWORD = "correct-horse-battery";
    private static final Pattern TOKEN = Pattern.compile("token=([A-Za-z0-9_-]+)");

    @Autowired MockMvc mvc;
    @MockitoBean EmailSender email;

    @Test
    void signingUpSendsAConfirmationLinkThatConfirmsTheEmail() throws Exception {
        String address = address();
        MvcResult registered = register(address).andExpect(jsonPath("$.user.emailVerified").value(false)).andReturn();
        String access = read(registered, "$.accessToken");

        String token = tokenFromEmailTo(address, "Confirm your email");
        call("/api/v1/auth/email/verify", "{\"token\":\"%s\"}".formatted(token)).andExpect(status().isNoContent());
        call("/api/v1/auth/email/verify", "{\"token\":\"%s\"}".formatted(token))
            .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + access))
            .andExpect(jsonPath("$.emailVerified").value(true));

        mvc.perform(post("/api/v1/auth/email/resend").header("Authorization", "Bearer " + access))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("EMAIL_ALREADY_VERIFIED"));
        call("/api/v1/auth/email/verify", "{\"token\":\"made-up\"}")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_OR_EXPIRED_LINK"));
    }

    @Test
    void aNewConfirmationLinkRetiresTheOldOne() throws Exception {
        String address = address();
        String access = read(register(address).andReturn(), "$.accessToken");
        String first = tokenFromEmailTo(address, "Confirm your email");

        mvc.perform(post("/api/v1/auth/email/resend").header("Authorization", "Bearer " + access))
            .andExpect(status().isAccepted());
        verify(email, timeout(3000).times(2)).send(argThat(m -> m != null && m.to().equals(address)));

        call("/api/v1/auth/email/verify", "{\"token\":\"%s\"}".formatted(first))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_OR_EXPIRED_LINK"));
    }

    @Test
    void aForgottenPasswordCanBeResetOnceAndSignsOutEveryDevice() throws Exception {
        String address = address();
        String refresh = read(register(address).andReturn(), "$.refreshToken");

        call("/api/v1/auth/password/forgot", "{\"email\":\"%s\"}".formatted(address.toUpperCase()))
            .andExpect(status().isAccepted());
        String token = tokenFromEmailTo(address, "Reset your MyWatchMate password");

        String reset = "{\"token\":\"%s\",\"newPassword\":\"new-battery-staple\"}".formatted(token);
        call("/api/v1/auth/password/reset", reset).andExpect(status().isNoContent());
        verify(email, timeout(3000))
            .send(argThat(m -> m != null && m.to().equals(address) && m.subject().contains("password was changed")));

        call("/api/v1/auth/password/reset", reset)
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_OR_EXPIRED_LINK"));
        call("/api/v1/auth/login", "{\"email\":\"%s\",\"password\":\"%s\"}".formatted(address, PASSWORD))
            .andExpect(status().isUnauthorized());
        call("/api/v1/auth/refresh", "{\"refreshToken\":\"%s\"}".formatted(refresh))
            .andExpect(status().isUnauthorized());
        call("/api/v1/auth/login", "{\"email\":\"%s\",\"password\":\"new-battery-staple\"}".formatted(address))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.user.emailVerified").value(true));
    }

    @Test
    void anUnknownAddressLooksTheSameButSendsNothing() throws Exception {
        String nobody = address();

        call("/api/v1/auth/password/forgot", "{\"email\":\"%s\"}".formatted(nobody)).andExpect(status().isAccepted());

        verify(email, after(500).never()).send(argThat(m -> m != null && m.to().equals(nobody)));
    }

    // ---- helpers -------------------------------------------------------------

    private String tokenFromEmailTo(String address, String subject) {
        // Emails go out in the background: wait for this one
        verify(email, timeout(3000).atLeastOnce())
            .send(argThat(m -> m != null && m.to().equals(address) && m.subject().contains(subject)));
        ArgumentCaptor<EmailMessage> sent = ArgumentCaptor.forClass(EmailMessage.class);
        verify(email, atLeastOnce()).send(sent.capture());
        EmailMessage message = sent.getAllValues().stream()
            .filter(m -> m.to().equals(address) && m.subject().contains(subject))
            .reduce((first, second) -> second)
            .orElseThrow(() -> new AssertionError("No \"" + subject + "\" email to " + address));
        Matcher matcher = TOKEN.matcher(message.text());
        assertThat(matcher.find()).isTrue();
        return matcher.group(1);
    }

    private static String address() {
        return "account-" + UUID.randomUUID() + "@example.com";
    }

    private ResultActions register(String address) throws Exception {
        return call("/api/v1/auth/register", """
            {"email":"%s","password":"%s","displayName":"Ada"}""".formatted(address, PASSWORD))
            .andExpect(status().isCreated());
    }

    private ResultActions call(String path, String body) throws Exception {
        return mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private static String read(MvcResult result, String path) throws Exception {
        return JsonPath.read(result.getResponse().getContentAsString(), path).toString();
    }
}
