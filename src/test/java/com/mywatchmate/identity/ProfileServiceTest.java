package com.mywatchmate.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.mywatchmate.auth.AuthService;
import com.mywatchmate.auth.RefreshTokenRepository;
import com.mywatchmate.auth.dto.TokenResponse;
import com.mywatchmate.common.ApiException;
import com.mywatchmate.identity.dto.ChangePasswordRequest;
import com.mywatchmate.identity.dto.MeResponse;
import com.mywatchmate.identity.dto.UpdateProfileRequest;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class ProfileServiceTest {

    @Mock UserRepository users;
    @Mock RefreshTokenRepository refreshTokens;
    @Mock PasswordEncoder passwordEncoder;
    @Mock AuthService authService;

    private ProfileService service;
    private User ada;

    @BeforeEach
    void setUp() {
        service = new ProfileService(users, refreshTokens, passwordEncoder, authService);
        ada = new User("ada@example.com", "Ada");
        ada.setId(UUID.randomUUID());
        ada.setPasswordHash("{bcrypt}old");
        ada.setTimezone("Europe/London");
    }

    @Test
    void meReturnsTheProfile() {
        when(users.findById(ada.getId())).thenReturn(Optional.of(ada));

        MeResponse me = service.me(ada.getId());

        assertThat(me.id()).isEqualTo(ada.getId());
        assertThat(me.email()).isEqualTo("ada@example.com");
        assertThat(me.displayName()).isEqualTo("Ada");
        assertThat(me.timezone()).isEqualTo("Europe/London");
    }

    @Test
    void meForADeletedAccountIsNotFound() {
        UUID gone = UUID.randomUUID();
        when(users.findById(gone)).thenReturn(Optional.empty());

        assertApiError(() -> service.me(gone), HttpStatus.NOT_FOUND, "USER_NOT_FOUND");
    }

    @Test
    void updateChangesOnlyTheFieldsSent() {
        when(users.findById(ada.getId())).thenReturn(Optional.of(ada));
        when(users.saveAndFlush(ada)).thenReturn(ada);

        MeResponse me = service.update(ada.getId(), new UpdateProfileRequest("  Ada O. ", null));

        assertThat(me.displayName()).isEqualTo("Ada O.");
        assertThat(me.timezone()).isEqualTo("Europe/London");
    }

    @Test
    void updateNormalisesTimezone() {
        when(users.findById(ada.getId())).thenReturn(Optional.of(ada));
        when(users.saveAndFlush(ada)).thenReturn(ada);

        MeResponse me = service.update(ada.getId(), new UpdateProfileRequest(null, " Africa/Lagos "));

        assertThat(me.timezone()).isEqualTo("Africa/Lagos");
        assertThat(me.displayName()).isEqualTo("Ada");
    }

    @Test
    void updateRejectsUnknownTimezoneAndSavesNothing() {
        when(users.findById(ada.getId())).thenReturn(Optional.of(ada));

        assertApiError(() -> service.update(ada.getId(), new UpdateProfileRequest(null, "Nowhere/Land")),
            HttpStatus.BAD_REQUEST, "INVALID_TIMEZONE");
        verify(users, never()).saveAndFlush(any());
    }

    @Test
    void changePasswordRejectsWrongCurrentPassword() {
        when(users.findById(ada.getId())).thenReturn(Optional.of(ada));
        when(passwordEncoder.matches("not-it", "{bcrypt}old")).thenReturn(false);

        assertApiError(() -> service.changePassword(ada.getId(), new ChangePasswordRequest("not-it", "new-password-123"), null),
            HttpStatus.BAD_REQUEST, "CURRENT_PASSWORD_INCORRECT");
        verifyNoInteractions(refreshTokens);
        verify(authService, never()).issueTokens(any(), any(), any());
        assertThat(ada.getPasswordHash()).isEqualTo("{bcrypt}old");
    }

    @Test
    void changePasswordFailsForAccountsWithoutAPassword() {
        ada.setPasswordHash(null);
        when(users.findById(ada.getId())).thenReturn(Optional.of(ada));

        assertApiError(() -> service.changePassword(ada.getId(), new ChangePasswordRequest("anything", "new-password-123"), null),
            HttpStatus.BAD_REQUEST, "CURRENT_PASSWORD_INCORRECT");
        verify(passwordEncoder, never()).matches(anyString(), anyString());
    }

    @Test
    void changePasswordStoresNewHashSignsOutEverywhereThenIssuesFreshTokens() {
        TokenResponse fresh = TokenResponse.of("new-access", 900, "new-refresh", MeResponse.from(ada));
        when(users.findById(ada.getId())).thenReturn(Optional.of(ada));
        when(passwordEncoder.matches("old-password", "{bcrypt}old")).thenReturn(true);
        when(passwordEncoder.encode("new-password-123")).thenReturn("{bcrypt}new");
        when(authService.issueTokens(eq(ada), any(UUID.class), eq("ua"))).thenReturn(fresh);

        TokenResponse response = service.changePassword(ada.getId(),
            new ChangePasswordRequest("old-password", "new-password-123"), "ua");

        assertThat(response).isSameAs(fresh);
        assertThat(ada.getPasswordHash()).isEqualTo("{bcrypt}new");
        InOrder order = inOrder(authService, refreshTokens);
        order.verify(authService).requireBcryptLength("new-password-123");
        order.verify(refreshTokens).revokeAllForUser(eq(ada.getId()), any(Instant.class));
        order.verify(authService).issueTokens(eq(ada), any(UUID.class), eq("ua"));
    }

    private static void assertApiError(ThrowingCallable call, HttpStatus status, String code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.getStatus()).isEqualTo(status);
            assertThat(e.getCode()).isEqualTo(code);
        });
    }
}
