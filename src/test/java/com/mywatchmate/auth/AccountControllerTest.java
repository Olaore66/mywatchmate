package com.mywatchmate.auth;

import static com.mywatchmate.web.WebLayerTestConfig.asGuest;
import static com.mywatchmate.web.WebLayerTestConfig.asUser;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mywatchmate.common.ApiException;
import com.mywatchmate.web.WebLayerTestConfig;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AccountController.class)
@Import(WebLayerTestConfig.class)
class AccountControllerTest {

    @Autowired MockMvc mvc;
    @MockitoBean AccountService accounts;

    @Test
    void forgotPasswordNeedsNoSignInAndAlwaysSaysAccepted() throws Exception {
        mvc.perform(post("/api/v1/auth/password/forgot").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"ada@example.com\"}"))
            .andExpect(status().isAccepted());

        verify(accounts).forgotPassword("ada@example.com");
    }

    @Test
    void resetAndVerifyNeedNoSignIn() throws Exception {
        mvc.perform(post("/api/v1/auth/password/reset").contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"abc\",\"newPassword\":\"new-battery-staple\"}"))
            .andExpect(status().isNoContent());
        mvc.perform(post("/api/v1/auth/email/verify").contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"abc\"}"))
            .andExpect(status().isNoContent());

        verify(accounts).resetPassword("abc", "new-battery-staple");
        verify(accounts).verifyEmail("abc");
    }

    @Test
    void badInputIsRejectedBeforeTheService() throws Exception {
        for (String[] request : new String[][] {
            {"/api/v1/auth/password/forgot", "{\"email\":\"not-an-email\"}"},
            {"/api/v1/auth/password/reset", "{\"token\":\"abc\",\"newPassword\":\"short\"}"},
            {"/api/v1/auth/password/reset", "{\"newPassword\":\"new-battery-staple\"}"},
            {"/api/v1/auth/email/verify", "{\"token\":\" \"}"}}) {
            mvc.perform(post(request[0]).contentType(MediaType.APPLICATION_JSON).content(request[1]))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
        verifyNoInteractions(accounts);
    }

    @Test
    void aBadLinkKeepsItsCode() throws Exception {
        doThrow(ApiException.badRequest("INVALID_OR_EXPIRED_LINK", "expired")).when(accounts).verifyEmail("old");

        mvc.perform(post("/api/v1/auth/email/verify").contentType(MediaType.APPLICATION_JSON).content("{\"token\":\"old\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_OR_EXPIRED_LINK"));
    }

    @Test
    void resendIsForSignedInUsersOnly() throws Exception {
        UUID ada = UUID.randomUUID();
        mvc.perform(post("/api/v1/auth/email/resend").with(asUser(ada))).andExpect(status().isAccepted());
        verify(accounts).resendVerification(ada);

        mvc.perform(post("/api/v1/auth/email/resend")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/auth/email/resend").with(asGuest(UUID.randomUUID(), UUID.randomUUID())))
            .andExpect(status().isForbidden());
    }
}
