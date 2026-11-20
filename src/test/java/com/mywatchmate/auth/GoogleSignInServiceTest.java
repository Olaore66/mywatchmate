package com.mywatchmate.auth;

import static com.mywatchmate.testsupport.Fixtures.MONDAY_MIDNIGHT;
import static com.mywatchmate.testsupport.Fixtures.assertApiError;
import static com.mywatchmate.testsupport.Fixtures.clockAt;
import static com.mywatchmate.testsupport.Fixtures.guest;
import static com.mywatchmate.testsupport.Fixtures.space;
import static com.mywatchmate.testsupport.Fixtures.user;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mywatchmate.auth.GoogleTokenVerifier.GoogleAccount;
import com.mywatchmate.auth.UserIdentity.Provider;
import com.mywatchmate.auth.dto.TokenResponse;
import com.mywatchmate.identity.User;
import com.mywatchmate.identity.UserRepository;
import com.mywatchmate.track.StreakService;
import com.mywatchmate.watch.Participant;
import com.mywatchmate.watch.ParticipantRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;

@ExtendWith(MockitoExtension.class)
class GoogleSignInServiceTest {

    private static final GoogleAccount ADA = new GoogleAccount("g-123", "ada@gmail.com", "Ada Okafor");

    @Mock GoogleTokenVerifier verifier;
    @Mock UserIdentityRepository identities;
    @Mock UserRepository users;
    @Mock ParticipantRepository participants;
    @Mock StreakService streaks;
    @Mock RefreshTokenRepository refreshTokens;
    @Mock AuthService auth;

    private GoogleSignInService service;
    private final TokenResponse tokens = TokenResponse.of("access", 900, "refresh", null);

    @BeforeEach
    void setUp() {
        service = new GoogleSignInService(verifier, identities, users, participants, streaks, refreshTokens, auth,
            clockAt(MONDAY_MIDNIGHT));
        lenient().when(verifier.verify("id-token")).thenReturn(ADA);
        lenient().when(auth.issueTokens(any(), any(), eq("Phone"))).thenReturn(tokens);
        lenient().when(users.saveAndFlush(any(User.class))).thenAnswer(i -> {
            User saved = i.getArgument(0);
            saved.setId(UUID.randomUUID());
            return saved;
        });
    }

    private UserIdentity identityOf(User owner) {
        return new UserIdentity(owner, Provider.GOOGLE, ADA.subject(), ADA.email(), MONDAY_MIDNIGHT);
    }

    private UserIdentity savedIdentity() {
        ArgumentCaptor<UserIdentity> saved = ArgumentCaptor.forClass(UserIdentity.class);
        verify(identities).saveAndFlush(saved.capture());
        return saved.getValue();
    }

    @Nested
    class SigningIn {

        @Test
        void aGoogleAccountSeenBeforeSignsIntoItsAccount() {
            User ada = user("Ada");
            when(identities.findWithUser(Provider.GOOGLE, "g-123")).thenReturn(Optional.of(identityOf(ada)));

            assertThat(service.signIn("id-token", null, "Phone")).isSameAs(tokens);

            verify(auth).issueTokens(eq(ada), any(), eq("Phone"));
            verify(users, never()).saveAndFlush(any());
            verify(identities, never()).saveAndFlush(any());
        }

        @Test
        void aVerifiedAccountWithTheSameEmailIsLinkedAndKeepsItsPassword() {
            User ada = user("Ada");
            ada.setPasswordHash("{bcrypt}hash");
            ada.markEmailVerified(MONDAY_MIDNIGHT.minusSeconds(3600));
            when(users.findByEmailIgnoreCase("ada@gmail.com")).thenReturn(Optional.of(ada));
            when(identities.findByUserIdAndProvider(ada.getId(), Provider.GOOGLE)).thenReturn(Optional.empty());

            service.signIn("id-token", null, "Phone");

            assertThat(savedIdentity().getUser()).isSameAs(ada);
            assertThat(ada.getPasswordHash()).isEqualTo("{bcrypt}hash");
            verify(refreshTokens, never()).revokeAllForUser(any(), any());
            verify(auth).issueTokens(eq(ada), any(), eq("Phone"));
        }

        @Test
        void anUnverifiedAccountWithTheSameEmailLosesItsPasswordAndSignInsWhenLinked() {
            User squatter = user("Ada");
            squatter.setPasswordHash("{bcrypt}someone-elses");
            when(users.findByEmailIgnoreCase("ada@gmail.com")).thenReturn(Optional.of(squatter));
            when(identities.findByUserIdAndProvider(squatter.getId(), Provider.GOOGLE)).thenReturn(Optional.empty());

            service.signIn("id-token", null, "Phone");

            assertThat(squatter.getPasswordHash()).isNull();
            assertThat(squatter.isEmailVerified()).isTrue();
            verify(refreshTokens).revokeAllForUser(squatter.getId(), MONDAY_MIDNIGHT);
        }

        @Test
        void anEmailLinkedToAnotherGoogleAccountIsNotTakenOver() {
            User ada = user("Ada");
            when(users.findByEmailIgnoreCase("ada@gmail.com")).thenReturn(Optional.of(ada));
            when(identities.findByUserIdAndProvider(ada.getId(), Provider.GOOGLE))
                .thenReturn(Optional.of(new UserIdentity(ada, Provider.GOOGLE, "g-other", "ada@gmail.com", MONDAY_MIDNIGHT)));

            assertApiError(() -> service.signIn("id-token", null, "Phone"), HttpStatus.CONFLICT, "GOOGLE_ACCOUNT_MISMATCH");
            verify(identities, never()).saveAndFlush(any());
        }

        @Test
        void someoneNewGetsAVerifiedAccountWithNoPassword() {
            service.signIn("id-token", "Africa/Lagos", "Phone");

            ArgumentCaptor<User> created = ArgumentCaptor.forClass(User.class);
            verify(users).saveAndFlush(created.capture());
            User ada = created.getValue();
            assertThat(ada.getEmail()).isEqualTo("ada@gmail.com");
            assertThat(ada.getDisplayName()).isEqualTo("Ada Okafor");
            assertThat(ada.getTimezone()).isEqualTo("Africa/Lagos");
            assertThat(ada.getPasswordHash()).isNull();
            assertThat(ada.isEmailVerified()).isTrue();
            assertThat(savedIdentity().getSubject()).isEqualTo("g-123");
        }

        @Test
        void tappingTwiceAtOnceIsAConflictNotAnError() {
            when(users.saveAndFlush(any(User.class))).thenThrow(new DataIntegrityViolationException("duplicate"));

            assertApiError(() -> service.signIn("id-token", null, "Phone"), HttpStatus.CONFLICT, "SIGN_IN_IN_PROGRESS");
        }

        @Test
        void aMissingOrLongNameFallsBackSensibly() {
            assertThat(GoogleSignInService.displayName(new GoogleAccount("s", "bisi.ade@gmail.com", null))).isEqualTo("bisi.ade");
            assertThat(GoogleSignInService.displayName(new GoogleAccount("s", "bisi@gmail.com", "  "))).isEqualTo("bisi");
            assertThat(GoogleSignInService.displayName(new GoogleAccount("s", "b@x.com", "x".repeat(150)))).hasSize(100);
        }
    }

    @Nested
    class UpgradingAGuest {

        private Participant bisi;
        private AuthPrincipal principal;

        @BeforeEach
        void aGuest() {
            bisi = guest(space(user("Ada")), "Bisi");
            principal = AuthPrincipal.guest(bisi.getId(), bisi.getSpace().getId());
            lenient().when(participants.findById(bisi.getId())).thenReturn(Optional.of(bisi));
        }

        @Test
        void theGuestsPlaceMovesToTheirNewGoogleAccount() {
            service.upgradeGuest(principal, "id-token", null, "Phone");

            assertThat(bisi.isGuest()).isFalse();
            assertThat(bisi.getUser().getEmail()).isEqualTo("ada@gmail.com");
            assertThat(bisi.getUser().isEmailVerified()).isTrue();
            verify(streaks).recompute(bisi.getUser().getId());
        }

        @Test
        void someoneWhoAlreadyHasAnAccountSignsInInstead() {
            when(users.existsByEmailIgnoreCase("ada@gmail.com")).thenReturn(true);

            assertApiError(() -> service.upgradeGuest(principal, "id-token", null, "Phone"), HttpStatus.CONFLICT, "EMAIL_TAKEN");
            assertThat(bisi.isGuest()).isTrue();
        }

        @Test
        void aGuestCanOnlyUpgradeOnce() {
            bisi.attachToUser(user("Bisi"));

            assertApiError(() -> service.upgradeGuest(principal, "id-token", null, "Phone"), HttpStatus.CONFLICT, "ALREADY_UPGRADED");
        }

        @Test
        void aGoneGuestIsUnauthorised() {
            UUID gone = UUID.randomUUID();
            when(participants.findById(gone)).thenReturn(Optional.empty());

            assertApiError(() -> service.upgradeGuest(AuthPrincipal.guest(gone, UUID.randomUUID()), "id-token", null, "Phone"),
                HttpStatus.UNAUTHORIZED, "GUEST_NOT_FOUND");
        }
    }
}
