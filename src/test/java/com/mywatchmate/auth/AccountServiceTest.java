package com.mywatchmate.auth;

import static com.mywatchmate.testsupport.Fixtures.MONDAY_MIDNIGHT;
import static com.mywatchmate.testsupport.Fixtures.assertApiError;
import static com.mywatchmate.testsupport.Fixtures.clockAt;
import static com.mywatchmate.testsupport.Fixtures.user;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.mywatchmate.auth.AccountToken.Purpose;
import com.mywatchmate.common.ApiException;
import com.mywatchmate.identity.User;
import com.mywatchmate.identity.UserRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class AccountServiceTest {

    private static final Instant NOW = MONDAY_MIDNIGHT;

    @Mock UserRepository users;
    @Mock AccountTokenRepository accountTokens;
    @Mock RefreshTokenRepository refreshTokens;
    @Mock AccountLinks links;
    @Mock AuthService auth;
    @Mock TokenService tokens;
    @Mock PasswordEncoder passwordEncoder;

    private AccountService service;
    private User ada;

    @BeforeEach
    void setUp() {
        service = new AccountService(users, accountTokens, refreshTokens, links, auth, tokens, passwordEncoder, clockAt(NOW));
        ada = user("Ada");
        lenient().when(tokens.hash(anyString())).thenAnswer(i -> "hash:" + i.getArgument(0));
        lenient().when(passwordEncoder.encode(anyString())).thenAnswer(i -> "{bcrypt}" + i.getArgument(0));
    }

    private AccountToken token(Purpose purpose, String raw, Instant expires) {
        AccountToken token = new AccountToken(ada, purpose, "hash:" + raw, NOW.minusSeconds(60), expires);
        lenient().when(accountTokens.findForUpdate("hash:" + raw, purpose)).thenReturn(Optional.of(token));
        return token;
    }

    @Test
    void aKnownAddressGetsAResetLinkWhateverItsCapitals() {
        when(users.findByEmailIgnoreCase("ada@example.com")).thenReturn(Optional.of(ada));

        service.forgotPassword("  ADA@Example.com ");

        verify(links).sendPasswordReset(ada);
    }

    @Test
    void anUnknownAddressGetsNothingAndNoError() {
        when(users.findByEmailIgnoreCase("nobody@example.com")).thenReturn(Optional.empty());

        service.forgotPassword("nobody@example.com");

        verifyNoInteractions(links);
    }

    @Test
    void repeatedResetRequestsStopQuietlyAfterTheLimit() {
        when(users.findByEmailIgnoreCase("ada@example.com")).thenReturn(Optional.of(ada));

        for (int i = 0; i < AccountService.MAX_EMAILS_PER_HOUR + 2; i++) {
            service.forgotPassword("ada@example.com");
        }

        verify(links, times(AccountService.MAX_EMAILS_PER_HOUR)).sendPasswordReset(ada);
    }

    @Test
    void resettingChangesThePasswordSignsOutEverywhereAndTellsThem() {
        AccountToken token = token(Purpose.PASSWORD_RESET, "good", NOW.plusSeconds(60));

        service.resetPassword("good", "new-battery-staple");

        assertThat(ada.getPasswordHash()).isEqualTo("{bcrypt}new-battery-staple");
        assertThat(token.getUsedAt()).isEqualTo(NOW);
        assertThat(ada.isEmailVerified()).as("they opened a link sent to it").isTrue();
        verify(refreshTokens).revokeAllForUser(ada.getId(), NOW);
        verify(links).sendPasswordChanged(ada);
    }

    @Test
    void aResetLinkThatIsUnknownExpiredUsedOrTheWrongKindIsRefused() {
        when(accountTokens.findForUpdate("hash:unknown", Purpose.PASSWORD_RESET)).thenReturn(Optional.empty());
        token(Purpose.PASSWORD_RESET, "expired", NOW);
        token(Purpose.PASSWORD_RESET, "used", NOW.plusSeconds(60)).use(NOW.minusSeconds(1));
        token(Purpose.EMAIL_VERIFY, "verify-link", NOW.plusSeconds(60));
        when(accountTokens.findForUpdate("hash:verify-link", Purpose.PASSWORD_RESET)).thenReturn(Optional.empty());

        for (String raw : new String[] {"unknown", "expired", "used", "verify-link"}) {
            assertApiError(() -> service.resetPassword(raw, "new-battery-staple"), HttpStatus.BAD_REQUEST, "INVALID_OR_EXPIRED_LINK");
        }

        assertThat(ada.getPasswordHash()).isNull();
        verify(refreshTokens, never()).revokeAllForUser(any(), any());
    }

    @Test
    void anOverlongPasswordIsRefusedBeforeTheLinkIsSpent() {
        AccountToken token = token(Purpose.PASSWORD_RESET, "good", NOW.plusSeconds(60));
        doThrow(ApiException.badRequest("PASSWORD_TOO_LONG", "too long")).when(auth).requireBcryptLength("long");

        assertApiError(() -> service.resetPassword("good", "long"), HttpStatus.BAD_REQUEST, "PASSWORD_TOO_LONG");

        assertThat(token.getUsedAt()).isNull();
    }

    @Test
    void verifyingConfirmsTheEmailAndAReloadStillSucceeds() {
        AccountToken token = token(Purpose.EMAIL_VERIFY, "good", NOW.plus(Duration.ofHours(1)));

        service.verifyEmail("good");
        service.verifyEmail("good");

        assertThat(ada.getEmailVerifiedAt()).isEqualTo(NOW);
        assertThat(token.getUsedAt()).isEqualTo(NOW);
    }

    @Test
    void aVerifyLinkThatIsUnknownExpiredOrRetiredIsRefused() {
        when(accountTokens.findForUpdate("hash:unknown", Purpose.EMAIL_VERIFY)).thenReturn(Optional.empty());
        token(Purpose.EMAIL_VERIFY, "expired", NOW);
        token(Purpose.EMAIL_VERIFY, "retired", NOW.plusSeconds(60)).use(NOW.minusSeconds(1));

        for (String raw : new String[] {"unknown", "expired", "retired"}) {
            assertApiError(() -> service.verifyEmail(raw), HttpStatus.BAD_REQUEST, "INVALID_OR_EXPIRED_LINK");
        }

        assertThat(ada.isEmailVerified()).isFalse();
    }

    @Test
    void resendingIsLimitedAndNotForConfirmedOrDeletedAccounts() {
        when(users.findById(ada.getId())).thenReturn(Optional.of(ada));

        for (int i = 0; i < AccountService.MAX_EMAILS_PER_HOUR; i++) {
            service.resendVerification(ada.getId());
        }
        verify(links, times(AccountService.MAX_EMAILS_PER_HOUR)).sendVerification(ada);
        assertApiError(() -> service.resendVerification(ada.getId()), HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_EMAILS");

        ada.markEmailVerified(NOW);
        assertApiError(() -> service.resendVerification(ada.getId()), HttpStatus.CONFLICT, "EMAIL_ALREADY_VERIFIED");

        UUID gone = UUID.randomUUID();
        when(users.findById(gone)).thenReturn(Optional.empty());
        assertApiError(() -> service.resendVerification(gone), HttpStatus.NOT_FOUND, "USER_NOT_FOUND");
    }
}
