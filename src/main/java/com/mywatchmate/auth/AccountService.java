package com.mywatchmate.auth;

import com.mywatchmate.common.ApiException;
import com.mywatchmate.common.SlidingWindowLimiter;
import com.mywatchmate.identity.User;
import com.mywatchmate.identity.UserRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Forgotten passwords and email confirmation, through one-time emailed links. Asking for a reset always
 * looks the same from outside, so nobody can use it to find out who has an account. Unverified accounts
 * still work; {@code emailVerified} on the profile lets the app nudge them.
 */
@Service
public class AccountService {

    static final int MAX_EMAILS_PER_HOUR = 3;

    private final UserRepository users;
    private final AccountTokenRepository accountTokens;
    private final RefreshTokenRepository refreshTokens;
    private final AccountLinks links;
    private final AuthService auth;
    private final TokenService tokens;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;
    private final SlidingWindowLimiter resetEmails;
    private final SlidingWindowLimiter verifyEmails;

    @Autowired
    public AccountService(UserRepository users, AccountTokenRepository accountTokens, RefreshTokenRepository refreshTokens,
                          AccountLinks links, AuthService auth, TokenService tokens, PasswordEncoder passwordEncoder,
                          Clock clock) {
        this.users = users;
        this.accountTokens = accountTokens;
        this.refreshTokens = refreshTokens;
        this.links = links;
        this.auth = auth;
        this.tokens = tokens;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
        this.resetEmails = new SlidingWindowLimiter(clock, MAX_EMAILS_PER_HOUR, Duration.ofHours(1), 10_000);
        this.verifyEmails = new SlidingWindowLimiter(clock, MAX_EMAILS_PER_HOUR, Duration.ofHours(1), 10_000);
    }

    /** Emails a reset link if the account exists. Unknown addresses and too many requests are silently ignored. */
    @Transactional
    public void forgotPassword(String email) {
        users.findByEmailIgnoreCase(email.trim().toLowerCase(Locale.ROOT))
            .filter(user -> resetEmails.tryAcquire(user.getId()))
            .ifPresent(links::sendPasswordReset);
    }

    /** Sets the new password and signs out every device. Reading the email also proves the address is theirs. */
    @Transactional
    public void resetPassword(String rawToken, String newPassword) {
        auth.requireBcryptLength(newPassword);
        Instant now = clock.instant();
        AccountToken token = accountTokens.findForUpdate(tokens.hash(rawToken), AccountToken.Purpose.PASSWORD_RESET)
            .filter(t -> t.isUsable(now))
            .orElseThrow(AccountService::invalidLink);
        token.use(now);
        User user = token.getUser();
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.markEmailVerified(now);
        refreshTokens.revokeAllForUser(user.getId(), now);
        links.sendPasswordChanged(user);
    }

    /** Opening the link twice (a double click, a page reload) still succeeds once the email is confirmed. */
    @Transactional
    public void verifyEmail(String rawToken) {
        Instant now = clock.instant();
        AccountToken token = accountTokens.findForUpdate(tokens.hash(rawToken), AccountToken.Purpose.EMAIL_VERIFY)
            .orElseThrow(AccountService::invalidLink);
        if (token.getUser().isEmailVerified() && token.getUsedAt() != null) {
            return;
        }
        if (!token.isUsable(now)) {
            throw invalidLink();
        }
        token.use(now);
        token.getUser().markEmailVerified(now);
    }

    /** A new confirmation email for the signed-in user; earlier links stop working. */
    @Transactional
    public void resendVerification(UUID userId) {
        User user = users.findById(userId)
            .orElseThrow(() -> ApiException.notFound("USER_NOT_FOUND", "This account no longer exists."));
        if (user.isEmailVerified()) {
            throw ApiException.conflict("EMAIL_ALREADY_VERIFIED", "Your email is already confirmed.");
        }
        if (!verifyEmails.tryAcquire(userId)) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_EMAILS",
                "We've sent several emails already. Check your spam folder, or try again in an hour.");
        }
        links.sendVerification(user);
    }

    private static ApiException invalidLink() {
        return ApiException.badRequest("INVALID_OR_EXPIRED_LINK", "This link is invalid or has expired. Please ask for a new one.");
    }
}
