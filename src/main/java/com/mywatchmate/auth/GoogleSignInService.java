package com.mywatchmate.auth;

import com.mywatchmate.auth.GoogleTokenVerifier.GoogleAccount;
import com.mywatchmate.auth.UserIdentity.Provider;
import com.mywatchmate.auth.dto.TokenResponse;
import com.mywatchmate.common.ApiException;
import com.mywatchmate.common.Timezones;
import com.mywatchmate.identity.User;
import com.mywatchmate.identity.UserRepository;
import com.mywatchmate.track.StreakService;
import com.mywatchmate.watch.Participant;
import com.mywatchmate.watch.ParticipantRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * "Sign in with Google", which also signs people up. In order:
 * <ol>
 *   <li>This Google account signed in before: that account.</li>
 *   <li>Its (Google-verified) email already has an account: link Google to it, and sign in.</li>
 *   <li>Otherwise: a new account with no password (they can set one later with "forgot password").</li>
 * </ol>
 * Google proved the email is theirs, so the account's email counts as verified either way. Linking to an account
 * whose email was never verified removes its password and signs it out everywhere: whoever chose that password
 * never proved they own the email.
 */
@Service
@RequiredArgsConstructor
public class GoogleSignInService {

    static final int MAX_NAME = 100;

    private final GoogleTokenVerifier verifier;
    private final UserIdentityRepository identities;
    private final UserRepository users;
    private final ParticipantRepository participants;
    private final StreakService streaks;
    private final RefreshTokenRepository refreshTokens;
    private final AuthService auth;
    private final Clock clock;

    @Transactional
    public TokenResponse signIn(String idToken, String timezone, String userAgent) {
        GoogleAccount google = verifier.verify(idToken);
        Instant now = clock.instant();
        User user = identities.findWithUser(Provider.GOOGLE, google.subject())
            .map(UserIdentity::getUser)
            .orElseGet(() -> users.findByEmailIgnoreCase(google.email())
                .map(existing -> link(existing, google, now))
                .orElseGet(() -> create(google, timezone, now)));
        user.markEmailVerified(now);
        return auth.issueTokens(user, UUID.randomUUID(), userAgent);
    }

    /** A guest creates their account with Google; their place in the room (chat, attendance) moves with them. */
    @Transactional
    public TokenResponse upgradeGuest(AuthPrincipal principal, String idToken, String timezone, String userAgent) {
        Participant guest = participants.findById(principal.participantId())
            .orElseThrow(() -> ApiException.unauthorized("GUEST_NOT_FOUND", "Guest session is no longer valid."));
        if (!guest.isGuest()) {
            throw ApiException.conflict("ALREADY_UPGRADED", "This guest already has an account. Please sign in.");
        }
        GoogleAccount google = verifier.verify(idToken);
        if (identities.findWithUser(Provider.GOOGLE, google.subject()).isPresent()
            || users.existsByEmailIgnoreCase(google.email())) {
            throw ApiException.conflict("EMAIL_TAKEN",
                "There's already an account for this Google account. Sign in with Google instead, then rejoin with the link.");
        }
        Instant now = clock.instant();
        User user = create(google, timezone, now);
        user.markEmailVerified(now);
        guest.attachToUser(user);
        streaks.recompute(user.getId());
        return auth.issueTokens(user, UUID.randomUUID(), userAgent);
    }

    private User link(User existing, GoogleAccount google, Instant now) {
        if (identities.findByUserIdAndProvider(existing.getId(), Provider.GOOGLE).isPresent()) {
            // Someone else's Google account now shows this email; don't move the account to it
            throw ApiException.conflict("GOOGLE_ACCOUNT_MISMATCH",
                "This email's account is linked to a different Google account. Sign in with that one, or with your password.");
        }
        if (!existing.isEmailVerified()) {
            // Nobody ever proved they own this email, and Google just did. Whoever set this password may not be
            // the owner (someone can sign up with another person's email and wait), so it goes, with its sign-ins.
            existing.setPasswordHash(null);
            refreshTokens.revokeAllForUser(existing.getId(), now);
        }
        identities.saveAndFlush(new UserIdentity(existing, Provider.GOOGLE, google.subject(), google.email(), now));
        return existing;
    }

    private User create(GoogleAccount google, String timezone, Instant now) {
        User user = new User(google.email(), displayName(google));
        user.setTimezone(Timezones.normalize(timezone));
        try {
            user = users.saveAndFlush(user);
            identities.saveAndFlush(new UserIdentity(user, Provider.GOOGLE, google.subject(), google.email(), now));
        } catch (DataIntegrityViolationException e) {
            // The same person tapping twice at once: the other attempt made the account
            throw ApiException.conflict("SIGN_IN_IN_PROGRESS", "You're already being signed in. Please try again.");
        }
        return user;
    }

    /** Google's name, or the start of the email if there isn't one. */
    static String displayName(GoogleAccount google) {
        String name = google.name() == null ? "" : google.name().strip();
        if (name.isEmpty()) {
            name = google.email().substring(0, Math.max(1, google.email().indexOf('@')));
        }
        return name.length() <= MAX_NAME ? name : name.substring(0, MAX_NAME);
    }
}
