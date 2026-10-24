package com.mywatchmate.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mywatchmate.auth.dto.GuestJoinRequest;
import com.mywatchmate.auth.dto.GuestTokenResponse;
import com.mywatchmate.auth.dto.LoginRequest;
import com.mywatchmate.auth.dto.RegisterRequest;
import com.mywatchmate.auth.dto.TokenResponse;
import com.mywatchmate.common.ApiException;
import com.mywatchmate.identity.User;
import com.mywatchmate.identity.UserRepository;
import com.mywatchmate.track.StreakService;
import com.mywatchmate.watch.Participant;
import com.mywatchmate.watch.ParticipantRepository;
import com.mywatchmate.watch.Space;
import com.mywatchmate.watch.MembershipService;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    private static final String PASSWORD = "correct-horse-battery";
    private static final String DUMMY_HASH = "{bcrypt}timing-equaliser-not-a-real-password";

    @Mock UserRepository users;
    @Mock RefreshTokenRepository refreshTokens;
    @Mock ParticipantRepository participants;
    @Mock MembershipService membership;
    @Mock TokenService tokens;
    @Mock PasswordEncoder passwordEncoder;
    @Mock StreakService streaks;

    private final JwtProperties props =
        new JwtProperties("x".repeat(32), "mywatchmate", Duration.ofMinutes(15), Duration.ofDays(30));
    private AuthService service;
    private int opaqueCounter;

    @BeforeEach
    void setUp() {
        // Predictable fakes: "encoding" prefixes, "hashing" prefixes, opaque tokens count up
        lenient().when(passwordEncoder.encode(anyString())).thenAnswer(i -> "{bcrypt}" + i.getArgument(0));
        lenient().when(tokens.hash(anyString())).thenAnswer(i -> "hash:" + i.getArgument(0));
        lenient().when(tokens.newOpaqueToken()).thenAnswer(i -> "opaque-" + (++opaqueCounter));
        lenient().when(tokens.userAccessToken(any())).thenAnswer(i -> "access-for-" + i.getArgument(0));
        lenient().when(tokens.guestAccessToken(any(), any())).thenAnswer(i -> "guest-access-for-" + i.getArgument(0));
        lenient().when(tokens.accessTokenSeconds()).thenReturn(900L);
        lenient().when(users.saveAndFlush(any(User.class))).thenAnswer(i -> withId(i.<User>getArgument(0)));

        service = new AuthService(users, refreshTokens, participants, membership, tokens, passwordEncoder, props, streaks);
        service.init();
    }

    @Nested
    class Register {

        @Test
        void createsNormalisedUserAndIssuesTokens() {
            TokenResponse response = service.register(
                new RegisterRequest("  Ada@Example.COM ", PASSWORD, "  Ada  ", "Europe/London"), "Mozilla/5.0");

            User saved = captureSavedUser();
            assertThat(saved.getEmail()).isEqualTo("ada@example.com");
            assertThat(saved.getDisplayName()).isEqualTo("Ada");
            assertThat(saved.getPasswordHash()).isEqualTo("{bcrypt}" + PASSWORD);
            assertThat(saved.getTimezone()).isEqualTo("Europe/London");
            verify(users).existsByEmailIgnoreCase("ada@example.com");

            RefreshToken refresh = captureSavedRefreshToken();
            assertThat(refresh.getUser()).isSameAs(saved);
            assertThat(refresh.getTokenHash()).isEqualTo("hash:opaque-1");
            assertThat(refresh.getUserAgent()).isEqualTo("Mozilla/5.0");
            assertThat(refresh.getExpiresAt()).isCloseTo(Instant.now().plus(Duration.ofDays(30)), within(1, ChronoUnit.MINUTES));

            assertThat(response.accessToken()).isEqualTo("access-for-" + saved.getId());
            assertThat(response.tokenType()).isEqualTo("Bearer");
            assertThat(response.expiresIn()).isEqualTo(900);
            assertThat(response.refreshToken()).isEqualTo("opaque-1");
            assertThat(response.user().email()).isEqualTo("ada@example.com");
        }

        @Test
        void defaultsTimezoneToUtc() {
            service.register(new RegisterRequest("ada@example.com", PASSWORD, "Ada", null), null);
            assertThat(captureSavedUser().getTimezone()).isEqualTo("UTC");
        }

        @Test
        void truncatesLongUserAgents() {
            service.register(new RegisterRequest("ada@example.com", PASSWORD, "Ada", null), "x".repeat(500));
            assertThat(captureSavedRefreshToken().getUserAgent()).hasSize(300);
        }

        @Test
        void rejectsTakenEmail() {
            when(users.existsByEmailIgnoreCase("ada@example.com")).thenReturn(true);

            assertApiError(() -> service.register(new RegisterRequest("ada@example.com", PASSWORD, "Ada", null), null),
                HttpStatus.CONFLICT, "EMAIL_TAKEN");
            verify(users, never()).saveAndFlush(any());
        }

        @Test
        void reportsEmailTakenWhenLosingASignUpRace() {
            when(users.saveAndFlush(any(User.class))).thenThrow(new DataIntegrityViolationException("ux_users_email"));

            assertApiError(() -> service.register(new RegisterRequest("ada@example.com", PASSWORD, "Ada", null), null),
                HttpStatus.CONFLICT, "EMAIL_TAKEN");
            verify(refreshTokens, never()).save(any());
        }

        @Test
        void rejectsPasswordsLongerThan72Bytes() {
            String emoji = "😀".repeat(19); // 19 characters but 76 UTF-8 bytes
            assertApiError(() -> service.register(new RegisterRequest("ada@example.com", emoji, "Ada", null), null),
                HttpStatus.BAD_REQUEST, "PASSWORD_TOO_LONG");
            verify(users, never()).saveAndFlush(any());
        }

        @Test
        void rejectsUnknownTimezone() {
            assertApiError(() -> service.register(new RegisterRequest("ada@example.com", PASSWORD, "Ada", "Mars/Base"), null),
                HttpStatus.BAD_REQUEST, "INVALID_TIMEZONE");
            verify(users, never()).saveAndFlush(any());
        }
    }

    @Nested
    class Login {

        @Test
        void succeedsWithCorrectPassword() {
            User ada = existingUser("ada@example.com", "{bcrypt}stored");
            when(users.findByEmailIgnoreCase("ada@example.com")).thenReturn(Optional.of(ada));
            when(passwordEncoder.matches(PASSWORD, "{bcrypt}stored")).thenReturn(true);

            TokenResponse response = service.login(new LoginRequest(" ADA@example.com ", PASSWORD), "ua");

            assertThat(response.accessToken()).isEqualTo("access-for-" + ada.getId());
            assertThat(captureSavedRefreshToken().getUser()).isSameAs(ada);
        }

        @Test
        void eachLoginStartsANewTokenFamily() {
            User ada = existingUser("ada@example.com", "{bcrypt}stored");
            when(users.findByEmailIgnoreCase("ada@example.com")).thenReturn(Optional.of(ada));
            when(passwordEncoder.matches(PASSWORD, "{bcrypt}stored")).thenReturn(true);

            service.login(new LoginRequest("ada@example.com", PASSWORD), "phone");
            service.login(new LoginRequest("ada@example.com", PASSWORD), "laptop");

            ArgumentCaptor<RefreshToken> saved = ArgumentCaptor.forClass(RefreshToken.class);
            verify(refreshTokens, times(2)).save(saved.capture());
            List<RefreshToken> both = saved.getAllValues();
            assertThat(both.get(0).getFamilyId()).isNotEqualTo(both.get(1).getFamilyId());
        }

        @Test
        void rejectsWrongPassword() {
            when(users.findByEmailIgnoreCase("ada@example.com"))
                .thenReturn(Optional.of(existingUser("ada@example.com", "{bcrypt}stored")));
            when(passwordEncoder.matches("wrong-password", "{bcrypt}stored")).thenReturn(false);

            assertApiError(() -> service.login(new LoginRequest("ada@example.com", "wrong-password"), null),
                HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS");
            verify(refreshTokens, never()).save(any());
        }

        @Test
        void unknownEmailStillChecksAPasswordSoTimingMatches() {
            when(users.findByEmailIgnoreCase("ghost@example.com")).thenReturn(Optional.empty());

            assertApiError(() -> service.login(new LoginRequest("ghost@example.com", "anything-at-all"), null),
                HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS");
            verify(passwordEncoder).matches("anything-at-all", DUMMY_HASH);
        }

        @Test
        void accountWithoutPasswordCannotLoginEvenIfDummyHashMatched() {
            when(users.findByEmailIgnoreCase("social@example.com"))
                .thenReturn(Optional.of(existingUser("social@example.com", null)));
            when(passwordEncoder.matches(PASSWORD, DUMMY_HASH)).thenReturn(true);

            assertApiError(() -> service.login(new LoginRequest("social@example.com", PASSWORD), null),
                HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS");
        }

        @Test
        void overlongPasswordIsRejectedWithoutHashing() {
            when(users.findByEmailIgnoreCase("ada@example.com"))
                .thenReturn(Optional.of(existingUser("ada@example.com", "{bcrypt}stored")));

            assertApiError(() -> service.login(new LoginRequest("ada@example.com", "a".repeat(73)), null),
                HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS");
            verify(passwordEncoder, never()).matches(any(), any());
        }
    }

    @Nested
    class Refresh {

        private final UUID family = UUID.randomUUID();
        private User ada;

        @BeforeEach
        void user() {
            ada = existingUser("ada@example.com", "{bcrypt}stored");
        }

        @Test
        void retiresTheOldTokenAndIssuesANewOneInTheSameFamily() {
            RefreshToken old = new RefreshToken(ada, "hash:old", family, Instant.now().plus(Duration.ofDays(1)), "ua");
            when(refreshTokens.findForUpdate("hash:old")).thenReturn(Optional.of(old));

            TokenResponse response = service.refresh("old", "ua");

            assertThat(old.isRevoked()).isTrue();
            RefreshToken next = captureSavedRefreshToken();
            assertThat(next.getFamilyId()).isEqualTo(family);
            assertThat(next.getUser()).isSameAs(ada);
            assertThat(response.refreshToken()).isEqualTo("opaque-1");
        }

        @Test
        void unknownTokenIsRejected() {
            when(refreshTokens.findForUpdate("hash:nope")).thenReturn(Optional.empty());

            assertApiError(() -> service.refresh("nope", null), HttpStatus.UNAUTHORIZED, "INVALID_REFRESH_TOKEN");
        }

        @Test
        void reusingARetiredTokenRevokesTheWholeFamily() {
            RefreshToken retired = new RefreshToken(ada, "hash:old", family, Instant.now().plus(Duration.ofDays(1)), "ua");
            retired.revoke(Instant.now().minusSeconds(60));
            when(refreshTokens.findForUpdate("hash:old")).thenReturn(Optional.of(retired));

            assertApiError(() -> service.refresh("old", null), HttpStatus.UNAUTHORIZED, "REFRESH_TOKEN_REUSED");
            verify(refreshTokens).revokeFamily(eq(family), any(Instant.class));
            verify(refreshTokens, never()).save(any());
        }

        @Test
        void expiredTokenIsRejectedWithoutRevokingTheFamily() {
            RefreshToken expired = new RefreshToken(ada, "hash:old", family, Instant.now().minusSeconds(1), "ua");
            when(refreshTokens.findForUpdate("hash:old")).thenReturn(Optional.of(expired));

            assertApiError(() -> service.refresh("old", null), HttpStatus.UNAUTHORIZED, "REFRESH_TOKEN_EXPIRED");
            verify(refreshTokens, never()).revokeFamily(any(), any());
            verify(refreshTokens, never()).save(any());
        }
    }

    @Nested
    class Logout {

        @Test
        void revokesTheFamilyOfTheCallersOwnToken() {
            User ada = existingUser("ada@example.com", "{bcrypt}stored");
            UUID family = UUID.randomUUID();
            when(refreshTokens.findByTokenHash("hash:mine"))
                .thenReturn(Optional.of(new RefreshToken(ada, "hash:mine", family, Instant.now().plusSeconds(60), null)));

            service.logout(AuthPrincipal.user(ada.getId()), "mine");

            verify(refreshTokens).revokeFamily(eq(family), any(Instant.class));
        }

        @Test
        void ignoresSomeoneElsesToken() {
            User other = existingUser("other@example.com", "{bcrypt}stored");
            when(refreshTokens.findByTokenHash("hash:theirs"))
                .thenReturn(Optional.of(new RefreshToken(other, "hash:theirs", UUID.randomUUID(), Instant.now().plusSeconds(60), null)));

            service.logout(AuthPrincipal.user(UUID.randomUUID()), "theirs");

            verify(refreshTokens, never()).revokeFamily(any(), any());
        }

        @Test
        void ignoresUnknownToken() {
            when(refreshTokens.findByTokenHash("hash:nope")).thenReturn(Optional.empty());

            service.logout(AuthPrincipal.user(UUID.randomUUID()), "nope");

            verify(refreshTokens, never()).revokeFamily(any(), any());
        }
    }

    @Nested
    class Guests {

        private Space space;

        @BeforeEach
        void space() {
            space = new Space(existingUser("owner@example.com", null), "6am prayer", "inv-1", Space.AudienceType.DIASPORA);
            space.setId(UUID.randomUUID());
        }

        @Test
        void joiningAdmitsAGuestWithAHashedDeviceTokenAndReturnsTokens() {
            Participant admitted = guest();
            when(membership.admitGuest("inv-1", "Bisi", "hash:opaque-1")).thenReturn(admitted);

            GuestTokenResponse response = service.joinAsGuest(new GuestJoinRequest("inv-1", "  Bisi "));

            assertThat(response.deviceToken()).isEqualTo("opaque-1");
            assertThat(response.accessToken()).isEqualTo("guest-access-for-" + admitted.getId());
            assertThat(response.participantId()).isEqualTo(admitted.getId());
            assertThat(response.spaceId()).isEqualTo(space.getId());
            assertThat(response.displayName()).isEqualTo("Bisi");
            verify(tokens).guestAccessToken(admitted.getId(), space.getId());
        }

        @Test
        void roomRefusalsFromMembershipPassThrough() {
            when(membership.admitGuest(eq("inv-1"), eq("Bisi"), anyString()))
                .thenThrow(ApiException.forbidden("GUESTS_NOT_ALLOWED", "no guests"));

            assertApiError(() -> service.joinAsGuest(new GuestJoinRequest("inv-1", "Bisi")),
                HttpStatus.FORBIDDEN, "GUESTS_NOT_ALLOWED");
        }

        @Test
        void refreshingReturnsANewAccessTokenButNoDeviceToken() {
            Participant guest = guest();
            when(participants.findByGuestTokenHash("hash:device")).thenReturn(Optional.of(guest));

            GuestTokenResponse response = service.refreshGuest("device");

            assertThat(response.accessToken()).isEqualTo("guest-access-for-" + guest.getId());
            assertThat(response.deviceToken()).isNull();
        }

        @Test
        void refreshFailsForUnknownDeviceToken() {
            when(participants.findByGuestTokenHash("hash:device")).thenReturn(Optional.empty());
            assertApiError(() -> service.refreshGuest("device"), HttpStatus.UNAUTHORIZED, "INVALID_DEVICE_TOKEN");
        }

        @Test
        void refreshFailsAfterTheGuestLeft() {
            Participant guest = guest();
            guest.setLeftAt(Instant.now());
            when(participants.findByGuestTokenHash("hash:device")).thenReturn(Optional.of(guest));

            assertApiError(() -> service.refreshGuest("device"), HttpStatus.UNAUTHORIZED, "INVALID_DEVICE_TOKEN");
        }

        @Test
        void refreshFailsOnceTheSpaceIsArchived() {
            space.setArchivedAt(Instant.now());
            when(participants.findByGuestTokenHash("hash:device")).thenReturn(Optional.of(guest()));

            assertApiError(() -> service.refreshGuest("device"), HttpStatus.UNAUTHORIZED, "INVALID_DEVICE_TOKEN");
        }

        @Test
        void refreshFailsForAGuestWhoAlreadyUpgraded() {
            Participant upgraded = guest();
            upgraded.attachToUser(existingUser("bisi@example.com", "{bcrypt}x"));
            when(participants.findByGuestTokenHash("hash:device")).thenReturn(Optional.of(upgraded));

            assertApiError(() -> service.refreshGuest("device"), HttpStatus.UNAUTHORIZED, "INVALID_DEVICE_TOKEN");
        }

        @Test
        void upgradingMovesTheParticipantToTheNewAccount() {
            Participant guest = guest();
            when(participants.findById(guest.getId())).thenReturn(Optional.of(guest));

            TokenResponse response = service.upgradeGuest(AuthPrincipal.guest(guest.getId(), space.getId()),
                new RegisterRequest("bisi@example.com", PASSWORD, "Bisi A.", "Africa/Lagos"), "ua");

            User created = captureSavedUser();
            assertThat(guest.getUser()).isSameAs(created);
            assertThat(guest.isGuest()).isFalse();
            assertThat(guest.getGuestTokenHash()).isNull();
            assertThat(guest.getGuestDisplayName()).isNull();
            assertThat(response.user().email()).isEqualTo("bisi@example.com");
            // Their guest attendance now counts towards a streak
            verify(streaks).recompute(created.getId());
        }

        @Test
        void upgradingTwiceIsAConflict() {
            Participant upgraded = guest();
            upgraded.attachToUser(existingUser("bisi@example.com", "{bcrypt}x"));
            when(participants.findById(upgraded.getId())).thenReturn(Optional.of(upgraded));

            assertApiError(() -> service.upgradeGuest(AuthPrincipal.guest(upgraded.getId(), space.getId()),
                    new RegisterRequest("new@example.com", PASSWORD, "Bisi", null), null),
                HttpStatus.CONFLICT, "ALREADY_UPGRADED");
            verify(users, never()).saveAndFlush(any());
        }

        @Test
        void upgradingAMissingGuestIsUnauthorized() {
            UUID gone = UUID.randomUUID();
            when(participants.findById(gone)).thenReturn(Optional.empty());

            assertApiError(() -> service.upgradeGuest(AuthPrincipal.guest(gone, space.getId()),
                    new RegisterRequest("new@example.com", PASSWORD, "Bisi", null), null),
                HttpStatus.UNAUTHORIZED, "GUEST_NOT_FOUND");
        }

        private Participant guest() {
            Participant guest = Participant.guest(space, "Bisi", "hash:device");
            guest.setId(UUID.randomUUID());
            return guest;
        }
    }

    // ---- helpers -------------------------------------------------------------

    private User captureSavedUser() {
        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(users).saveAndFlush(captor.capture());
        return captor.getValue();
    }

    private RefreshToken captureSavedRefreshToken() {
        ArgumentCaptor<RefreshToken> captor = ArgumentCaptor.forClass(RefreshToken.class);
        verify(refreshTokens).save(captor.capture());
        return captor.getValue();
    }

    private static User existingUser(String email, String passwordHash) {
        User user = withId(new User(email, "Someone"));
        user.setPasswordHash(passwordHash);
        return user;
    }

    private static User withId(User user) {
        user.setId(UUID.randomUUID());
        return user;
    }

    private static void assertApiError(ThrowingCallable call, HttpStatus status, String code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.getStatus()).isEqualTo(status);
            assertThat(e.getCode()).isEqualTo(code);
        });
    }
}
