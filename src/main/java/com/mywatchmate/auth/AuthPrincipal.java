package com.mywatchmate.auth;

import java.util.UUID;

/**
 * Who is calling, resolved from the access token. Controllers receive it with
 * {@code @AuthenticationPrincipal AuthPrincipal principal}.
 *
 * @param userId        set for registered users
 * @param participantId set for guests (a guest only exists inside one space)
 * @param spaceId       the guest's space
 */
public record AuthPrincipal(Kind kind, UUID userId, UUID participantId, UUID spaceId) {

    public enum Kind { USER, GUEST }

    public static AuthPrincipal user(UUID userId) {
        return new AuthPrincipal(Kind.USER, userId, null, null);
    }

    public static AuthPrincipal guest(UUID participantId, UUID spaceId) {
        return new AuthPrincipal(Kind.GUEST, null, participantId, spaceId);
    }

    public boolean isGuest() {
        return kind == Kind.GUEST;
    }
}
