package com.mywatchmate.identity;

import com.mywatchmate.auth.AuthService;
import com.mywatchmate.auth.RefreshTokenRepository;
import com.mywatchmate.auth.dto.TokenResponse;
import com.mywatchmate.common.ApiException;
import com.mywatchmate.common.Timezones;
import com.mywatchmate.identity.dto.ChangePasswordRequest;
import com.mywatchmate.identity.dto.MeResponse;
import com.mywatchmate.identity.dto.UpdateProfileRequest;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ProfileService {

    private final UserRepository users;
    private final RefreshTokenRepository refreshTokens;
    private final PasswordEncoder passwordEncoder;
    private final AuthService authService;

    @Transactional(readOnly = true)
    public MeResponse me(UUID userId) {
        return MeResponse.from(load(userId));
    }

    @Transactional
    public MeResponse update(UUID userId, UpdateProfileRequest request) {
        User user = load(userId);
        if (request.displayName() != null) {
            user.setDisplayName(request.displayName().trim());
        }
        if (request.timezone() != null) {
            user.setTimezone(Timezones.normalize(request.timezone()));
        }
        if (request.emailReminders() != null) {
            user.setEmailReminders(request.emailReminders());
        }
        return MeResponse.from(users.saveAndFlush(user));
    }

    /** Signs out every device, then returns a fresh login for this one. */
    @Transactional
    public TokenResponse changePassword(UUID userId, ChangePasswordRequest request, String userAgent) {
        User user = load(userId);
        if (user.getPasswordHash() == null || !passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())) {
            throw ApiException.badRequest("CURRENT_PASSWORD_INCORRECT", "Current password is incorrect.");
        }
        authService.requireBcryptLength(request.newPassword());
        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        refreshTokens.revokeAllForUser(userId, Instant.now());
        return authService.issueTokens(user, UUID.randomUUID(), userAgent);
    }

    private User load(UUID userId) {
        return users.findById(userId)
            .orElseThrow(() -> ApiException.notFound("USER_NOT_FOUND", "This account no longer exists."));
    }
}
