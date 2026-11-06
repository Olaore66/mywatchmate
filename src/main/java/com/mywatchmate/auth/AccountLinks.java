package com.mywatchmate.auth;

import com.mywatchmate.identity.User;
import com.mywatchmate.notify.AfterCommitMailer;
import com.mywatchmate.watch.SpaceProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Issues the one-time links we email. Only the newest of each kind works. Call inside a transaction. */
@Component
@RequiredArgsConstructor
public class AccountLinks {

    static final Duration VERIFY_TTL = Duration.ofHours(48);
    static final Duration RESET_TTL = Duration.ofHours(1);

    private final AccountTokenRepository accountTokens;
    private final TokenService tokens;
    private final AfterCommitMailer mailer;
    private final SpaceProperties spaceProps;
    private final Clock clock;

    public void sendVerification(User user) {
        String link = issue(user, AccountToken.Purpose.EMAIL_VERIFY, VERIFY_TTL, "/verify-email?token=");
        mailer.send(AccountEmail.verify(user, link));
    }

    public void sendPasswordReset(User user) {
        String link = issue(user, AccountToken.Purpose.PASSWORD_RESET, RESET_TTL, "/reset-password?token=");
        mailer.send(AccountEmail.passwordReset(user, link));
    }

    public void sendPasswordChanged(User user) {
        mailer.send(AccountEmail.passwordChanged(user));
    }

    private String issue(User user, AccountToken.Purpose purpose, Duration ttl, String path) {
        Instant now = clock.instant();
        accountTokens.retireOpen(user.getId(), purpose, now);
        String raw = tokens.newOpaqueToken();
        accountTokens.save(new AccountToken(user, purpose, tokens.hash(raw), now, now.plus(ttl)));
        return spaceProps.frontendUrl() + path + raw;
    }
}
