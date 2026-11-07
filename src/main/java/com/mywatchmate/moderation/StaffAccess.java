package com.mywatchmate.moderation;

import com.mywatchmate.auth.AuthPrincipal;
import com.mywatchmate.common.ApiException;
import com.mywatchmate.identity.User;
import com.mywatchmate.identity.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * The gate for staff screens. Checked in the database on every call, not taken from the access token, so
 * removing someone's staff role takes effect at once.
 */
@Component
@RequiredArgsConstructor
public class StaffAccess {

    private final UserRepository users;

    public User require(AuthPrincipal principal) {
        if (principal == null || principal.isGuest()) {
            throw notStaff();
        }
        return users.findById(principal.userId()).filter(User::isStaff).orElseThrow(StaffAccess::notStaff);
    }

    private static ApiException notStaff() {
        return ApiException.forbidden("STAFF_ONLY", "Only MyWatchMate staff can do this.");
    }
}
