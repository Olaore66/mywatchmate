package com.mywatchmate.auth;

import com.mywatchmate.auth.dto.GuestJoinRequest;
import com.mywatchmate.auth.dto.GuestTokenResponse;
import com.mywatchmate.auth.dto.LoginRequest;
import com.mywatchmate.auth.dto.RegisterRequest;
import com.mywatchmate.auth.dto.TokenResponse;
import com.mywatchmate.common.ApiException;
import com.mywatchmate.common.Timezones;
import com.mywatchmate.identity.User;
import com.mywatchmate.identity.UserRepository;
import com.mywatchmate.identity.dto.MeResponse;
import com.mywatchmate.watch.Participant;
import com.mywatchmate.watch.ParticipantRepository;
import com.mywatchmate.watch.Space;
import com.mywatchmate.watch.SpaceRepository;
import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuthService {

    private static final int BCRYPT_MAX_BYTES = 72;

    private final UserRepository users;
    private final RefreshTokenRepository refreshTokens;
    private final ParticipantRepository participants;
    private final SpaceRepository spaces;
    private final TokenService tokens;
    private final PasswordEncoder passwordEncoder;
    private final JwtProperties props;

    // Checked against when the email is unknown, so "no such user" takes as long as "wrong password"
    private String dummyHash;

    @PostConstruct
    void init() {
        dummyHash = passwordEncoder.encode("timing-equaliser-not-a-real-password");
    }

    @Transactional
    public TokenResponse register(RegisterRequest request, String userAgent) {
        User user = createUser(request);
        return issueTokens(user, UUID.randomUUID(), userAgent);
    }

    @Transactional
    public TokenResponse login(LoginRequest request, String userAgent) {
        User user = users.findByEmailIgnoreCase(normalizeEmail(request.email())).orElse(null);
        boolean hasPassword = user != null && user.getPasswordHash() != null;
        boolean matches = fitsBcrypt(request.password())
            && passwordEncoder.matches(request.password(), hasPassword ? user.getPasswordHash() : dummyHash);
        if (!hasPassword || !matches) {
            throw ApiException.unauthorized("INVALID_CREDENTIALS", "Email or password is incorrect.");
        }
        return issueTokens(user, UUID.randomUUID(), userAgent);
    }

    /** Single use: the presented token is retired and a new pair issued in the same family. */
    @Transactional(noRollbackFor = ApiException.class)
    public TokenResponse refresh(String rawRefreshToken, String userAgent) {
        Instant now = Instant.now();
        RefreshToken token = refreshTokens.findForUpdate(tokens.hash(rawRefreshToken))
            .orElseThrow(() -> ApiException.unauthorized("INVALID_REFRESH_TOKEN", "Refresh token is not valid."));
        if (token.isRevoked()) {
            // A retired token came back: someone else may hold a copy. Log out that whole login.
            refreshTokens.revokeFamily(token.getFamilyId(), now);
            throw ApiException.unauthorized("REFRESH_TOKEN_REUSED",
                "This refresh token was already used. Please sign in again.");
        }
        if (token.isExpired(now)) {
            throw ApiException.unauthorized("REFRESH_TOKEN_EXPIRED", "Session expired. Please sign in again.");
        }
        token.revoke(now);
        return issueTokens(token.getUser(), token.getFamilyId(), userAgent);
    }

    /** Ends this device's login. Unknown or foreign tokens are ignored so nothing is revealed. */
    @Transactional
    public void logout(AuthPrincipal principal, String rawRefreshToken) {
        refreshTokens.findByTokenHash(tokens.hash(rawRefreshToken))
            .filter(t -> t.getUser().getId().equals(principal.userId()))
            .ifPresent(t -> refreshTokens.revokeFamily(t.getFamilyId(), Instant.now()));
    }

    @Transactional
    public GuestTokenResponse joinAsGuest(GuestJoinRequest request) {
        Space space = spaces.findByInviteToken(request.inviteToken())
            .filter(s -> s.getArchivedAt() == null)
            .orElseThrow(() -> ApiException.notFound("INVITE_NOT_FOUND", "This invite link is invalid or no longer active."));
        if (!space.isAllowGuests()) {
            throw ApiException.forbidden("GUESTS_NOT_ALLOWED", "This space requires an account to join.");
        }
        String deviceToken = tokens.newOpaqueToken();
        Participant guest = participants.save(
            Participant.guest(space, request.displayName().trim(), tokens.hash(deviceToken)));
        return guestTokens(guest, deviceToken);
    }

    @Transactional(readOnly = true)
    public GuestTokenResponse refreshGuest(String rawDeviceToken) {
        Participant guest = participants.findByGuestTokenHash(tokens.hash(rawDeviceToken))
            .filter(p -> p.isGuest() && p.getLeftAt() == null && p.getSpace().getArchivedAt() == null)
            .orElseThrow(() -> ApiException.unauthorized("INVALID_DEVICE_TOKEN", "Guest session is no longer valid."));
        return guestTokens(guest, null);
    }

    /** A guest creates an account; their participant row (chat, attendance, gifts) moves with them. */
    @Transactional
    public TokenResponse upgradeGuest(AuthPrincipal principal, RegisterRequest request, String userAgent) {
        Participant guest = participants.findById(principal.participantId())
            .orElseThrow(() -> ApiException.unauthorized("GUEST_NOT_FOUND", "Guest session is no longer valid."));
        if (!guest.isGuest()) {
            throw ApiException.conflict("ALREADY_UPGRADED", "This guest already has an account. Please sign in.");
        }
        User user = createUser(request);
        guest.attachToUser(user);
        return issueTokens(user, UUID.randomUUID(), userAgent);
    }

    /** Creates a refresh token in the given family and a matching access token. */
    @Transactional
    public TokenResponse issueTokens(User user, UUID familyId, String userAgent) {
        String rawRefresh = tokens.newOpaqueToken();
        refreshTokens.save(new RefreshToken(user, tokens.hash(rawRefresh), familyId,
            Instant.now().plus(props.refreshTtl()), truncate(userAgent, 300)));
        return TokenResponse.of(tokens.userAccessToken(user.getId()), tokens.accessTokenSeconds(),
            rawRefresh, MeResponse.from(user));
    }

    /** BCrypt only reads the first 72 bytes; reject longer passwords instead of silently truncating. */
    public void requireBcryptLength(String password) {
        if (!fitsBcrypt(password)) {
            throw ApiException.badRequest("PASSWORD_TOO_LONG", "Password must be at most 72 bytes.");
        }
    }

    private User createUser(RegisterRequest request) {
        String email = normalizeEmail(request.email());
        if (users.existsByEmailIgnoreCase(email)) {
            throw emailTaken();
        }
        requireBcryptLength(request.password());
        User user = new User(email, request.displayName().trim());
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setTimezone(Timezones.normalize(request.timezone()));
        try {
            return users.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            throw emailTaken(); // lost a race with a simultaneous sign-up
        }
    }

    private GuestTokenResponse guestTokens(Participant guest, String deviceToken) {
        UUID spaceId = guest.getSpace().getId();
        return new GuestTokenResponse(tokens.guestAccessToken(guest.getId(), spaceId), "Bearer",
            tokens.accessTokenSeconds(), deviceToken, guest.getId(), spaceId, guest.getGuestDisplayName());
    }

    private static ApiException emailTaken() {
        return ApiException.conflict("EMAIL_TAKEN", "An account with this email already exists.");
    }

    private static boolean fitsBcrypt(String password) {
        return password.getBytes(StandardCharsets.UTF_8).length <= BCRYPT_MAX_BYTES;
    }

    private static String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
