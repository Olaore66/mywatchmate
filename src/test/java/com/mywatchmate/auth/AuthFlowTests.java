package com.mywatchmate.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.mywatchmate.identity.User;
import com.mywatchmate.identity.UserRepository;
import com.mywatchmate.watch.Participant;
import com.mywatchmate.watch.ParticipantRepository;
import com.mywatchmate.watch.Space;
import com.mywatchmate.watch.SpaceRepository;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AuthFlowTests {

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired SpaceRepository spaces;
    @Autowired ParticipantRepository participants;

    // ---- users ---------------------------------------------------------------

    @Test
    void registerThenLoginThenReadProfile() throws Exception {
        String email = uniqueEmail();
        mvc.perform(json(post("/api/v1/auth/register"), registerBody(email, "Ada", "Europe/London")))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.tokenType").value("Bearer"))
            .andExpect(jsonPath("$.expiresIn").value(900))
            .andExpect(jsonPath("$.refreshToken").isNotEmpty())
            .andExpect(jsonPath("$.user.email").value(email))
            .andExpect(jsonPath("$.user.timezone").value("Europe/London"));

        String access = read(mvc.perform(json(post("/api/v1/auth/login"), loginBody(email.toUpperCase(), PASSWORD)))
            .andExpect(status().isOk()).andReturn(), "$.accessToken");

        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + access))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.email").value(email))
            .andExpect(jsonPath("$.displayName").value("Ada"));
    }

    @Test
    void duplicateEmailIsRejected() throws Exception {
        String email = uniqueEmail();
        mvc.perform(json(post("/api/v1/auth/register"), registerBody(email, "Ada", null))).andExpect(status().isCreated());
        mvc.perform(json(post("/api/v1/auth/register"), registerBody(email, "Other", null)))
            .andExpect(status().isConflict())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.code").value("EMAIL_TAKEN"));
    }

    @Test
    void invalidRegistrationListsFieldErrors() throws Exception {
        mvc.perform(json(post("/api/v1/auth/register"), """
                {"email":"not-an-email","password":"short","displayName":""}"""))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
            .andExpect(jsonPath("$.errors.email").exists())
            .andExpect(jsonPath("$.errors.password").exists())
            .andExpect(jsonPath("$.errors.displayName").exists());
    }

    @Test
    void unknownTimezoneIsRejected() throws Exception {
        mvc.perform(json(post("/api/v1/auth/register"), registerBody(uniqueEmail(), "Ada", "Mars/Olympus")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_TIMEZONE"));
    }

    @Test
    void wrongPasswordAndUnknownEmailLookIdentical() throws Exception {
        String email = uniqueEmail();
        mvc.perform(json(post("/api/v1/auth/register"), registerBody(email, "Ada", null))).andExpect(status().isCreated());

        String wrongPassword = mvc.perform(json(post("/api/v1/auth/login"), loginBody(email, "wrong-password")))
            .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
            .andReturn().getResponse().getContentAsString();
        String unknownEmail = mvc.perform(json(post("/api/v1/auth/login"), loginBody(uniqueEmail(), "wrong-password")))
            .andExpect(status().isUnauthorized())
            .andReturn().getResponse().getContentAsString();

        assertThat(unknownEmail).isEqualTo(wrongPassword);
    }

    @Test
    void missingOrTamperedTokenIsRejected() throws Exception {
        mvc.perform(get("/api/v1/me"))
            .andExpect(status().isUnauthorized())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));

        String access = read(register(), "$.accessToken");
        String tampered = access.substring(0, access.length() - 4) + "AAAA";
        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + tampered))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void refreshRotatesAndReuseSignsOutTheWholeLogin() throws Exception {
        String first = read(register(), "$.refreshToken");

        MvcResult rotated = mvc.perform(json(post("/api/v1/auth/refresh"), refreshBody(first)))
            .andExpect(status().isOk()).andReturn();
        String second = read(rotated, "$.refreshToken");
        assertThat(second).isNotEqualTo(first);

        // The old token comes back: treated as theft, the whole family is revoked...
        mvc.perform(json(post("/api/v1/auth/refresh"), refreshBody(first)))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("REFRESH_TOKEN_REUSED"));
        // ...so even the newest token no longer works.
        mvc.perform(json(post("/api/v1/auth/refresh"), refreshBody(second)))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void logoutRevokesTheRefreshToken() throws Exception {
        MvcResult login = register();
        String access = read(login, "$.accessToken");
        String refresh = read(login, "$.refreshToken");

        mvc.perform(json(post("/api/v1/auth/logout"), refreshBody(refresh)).header("Authorization", "Bearer " + access))
            .andExpect(status().isNoContent());
        mvc.perform(json(post("/api/v1/auth/refresh"), refreshBody(refresh)))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void updateProfileAndValidateTimezone() throws Exception {
        String access = read(register(), "$.accessToken");

        mvc.perform(json(patch("/api/v1/me"), """
                {"displayName":"Ada O.","timezone":"Africa/Lagos"}""").header("Authorization", "Bearer " + access))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.displayName").value("Ada O."))
            .andExpect(jsonPath("$.timezone").value("Africa/Lagos"));

        mvc.perform(json(patch("/api/v1/me"), """
                {"timezone":"Nowhere/Land"}""").header("Authorization", "Bearer " + access))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_TIMEZONE"));
    }

    @Test
    void changingPasswordSignsOutOtherDevices() throws Exception {
        String email = uniqueEmail();
        MvcResult phone = mvc.perform(json(post("/api/v1/auth/register"), registerBody(email, "Ada", null))).andReturn();
        MvcResult laptop = mvc.perform(json(post("/api/v1/auth/login"), loginBody(email, PASSWORD))).andReturn();

        mvc.perform(json(put("/api/v1/me/password"), """
                {"currentPassword":"wrong-one","newPassword":"brand-new-password"}""")
                .header("Authorization", "Bearer " + read(phone, "$.accessToken")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("CURRENT_PASSWORD_INCORRECT"));

        MvcResult changed = mvc.perform(json(put("/api/v1/me/password"), """
                {"currentPassword":"%s","newPassword":"brand-new-password"}""".formatted(PASSWORD))
                .header("Authorization", "Bearer " + read(phone, "$.accessToken")))
            .andExpect(status().isOk()).andReturn();

        mvc.perform(json(post("/api/v1/auth/refresh"), refreshBody(read(laptop, "$.refreshToken"))))
            .andExpect(status().isUnauthorized());
        mvc.perform(json(post("/api/v1/auth/refresh"), refreshBody(read(changed, "$.refreshToken"))))
            .andExpect(status().isOk());
        mvc.perform(json(post("/api/v1/auth/login"), loginBody(email, "brand-new-password")))
            .andExpect(status().isOk());
    }

    // ---- guests --------------------------------------------------------------

    @Test
    void guestJoinsRefreshesAndUpgradesKeepingTheirParticipant() throws Exception {
        Space space = newSpace(true);

        MvcResult joined = mvc.perform(json(post("/api/v1/auth/guest"), """
                {"inviteToken":"%s","displayName":"Bisi"}""".formatted(space.getInviteToken())))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.deviceToken").isNotEmpty())
            .andExpect(jsonPath("$.spaceId").value(space.getId().toString()))
            .andExpect(jsonPath("$.displayName").value("Bisi"))
            .andReturn();
        String guestAccess = read(joined, "$.accessToken");
        String deviceToken = read(joined, "$.deviceToken");
        UUID participantId = UUID.fromString(read(joined, "$.participantId"));

        // Guests can't use user-only endpoints
        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + guestAccess))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        mvc.perform(json(post("/api/v1/auth/guest/refresh"), """
                {"deviceToken":"%s"}""".formatted(deviceToken)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.accessToken").isNotEmpty())
            .andExpect(jsonPath("$.deviceToken").doesNotExist());

        String email = uniqueEmail();
        mvc.perform(json(post("/api/v1/auth/upgrade"), registerBody(email, "Bisi A.", null))
                .header("Authorization", "Bearer " + guestAccess))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.user.email").value(email));

        Participant participant = participants.findById(participantId).orElseThrow();
        assertThat(participant.getUser()).isNotNull();
        assertThat(participant.getUser().getEmail()).isEqualTo(email);
        assertThat(participant.getGuestTokenHash()).isNull();

        // The old guest device token stops working once upgraded
        mvc.perform(json(post("/api/v1/auth/guest/refresh"), """
                {"deviceToken":"%s"}""".formatted(deviceToken)))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("INVALID_DEVICE_TOKEN"));
    }

    @Test
    void guestJoinFailsForUnknownInviteOrMembersOnlySpace() throws Exception {
        mvc.perform(json(post("/api/v1/auth/guest"), """
                {"inviteToken":"no-such-invite","displayName":"Bisi"}"""))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("INVITE_NOT_FOUND"));

        Space membersOnly = newSpace(false);
        mvc.perform(json(post("/api/v1/auth/guest"), """
                {"inviteToken":"%s","displayName":"Bisi"}""".formatted(membersOnly.getInviteToken())))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("GUESTS_NOT_ALLOWED"));
    }

    @Test
    void usersCannotCallGuestUpgrade() throws Exception {
        String access = read(register(), "$.accessToken");
        mvc.perform(json(post("/api/v1/auth/upgrade"), registerBody(uniqueEmail(), "X", null))
                .header("Authorization", "Bearer " + access))
            .andExpect(status().isForbidden());
    }

    // ---- docs ----------------------------------------------------------------

    @Test
    void swaggerDocsAreServedAndListAuthEndpoints() throws Exception {
        mvc.perform(get("/v3/api-docs"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.paths['/api/v1/auth/login']").exists())
            .andExpect(jsonPath("$.paths['/api/v1/auth/guest']").exists())
            .andExpect(jsonPath("$.paths['/api/v1/me']").exists())
            .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.scheme").value("bearer"));
        mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
    }

    // ---- helpers -------------------------------------------------------------

    private static final String PASSWORD = "correct-horse-battery";

    private MvcResult register() throws Exception {
        return mvc.perform(json(post("/api/v1/auth/register"), registerBody(uniqueEmail(), "Ada", null)))
            .andExpect(status().isCreated()).andReturn();
    }

    private Space newSpace(boolean allowGuests) {
        User owner = users.save(new User(uniqueEmail(), "Owner"));
        Space space = new Space(owner, "6am prayer", "inv-" + UUID.randomUUID(), Space.AudienceType.DIASPORA);
        space.setAllowGuests(allowGuests);
        return spaces.save(space);
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static String registerBody(String email, String name, String timezone) {
        return """
            {"email":"%s","password":"%s","displayName":"%s"%s}""".formatted(email, PASSWORD, name,
            timezone == null ? "" : ",\"timezone\":\"" + timezone + "\"");
    }

    private static String loginBody(String email, String password) {
        return """
            {"email":"%s","password":"%s"}""".formatted(email, password);
    }

    private static String refreshBody(String refreshToken) {
        return """
            {"refreshToken":"%s"}""".formatted(refreshToken);
    }

    private static String read(MvcResult result, String path) throws Exception {
        return JsonPath.read(result.getResponse().getContentAsString(), path).toString();
    }

    private static String uniqueEmail() {
        return "user-" + UUID.randomUUID() + "@example.com";
    }
}
