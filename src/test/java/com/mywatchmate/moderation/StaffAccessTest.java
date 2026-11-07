package com.mywatchmate.moderation;

import static com.mywatchmate.testsupport.Fixtures.assertApiError;
import static com.mywatchmate.testsupport.Fixtures.user;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.mywatchmate.auth.AuthPrincipal;
import com.mywatchmate.identity.User;
import com.mywatchmate.identity.UserRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

@ExtendWith(MockitoExtension.class)
class StaffAccessTest {

    @Mock UserRepository users;

    @Test
    void staffOfEitherRoleGetIn() {
        StaffAccess access = new StaffAccess(users);
        for (User.StaffRole role : User.StaffRole.values()) {
            User rita = user("Rita");
            rita.setStaffRole(role);
            when(users.findById(rita.getId())).thenReturn(Optional.of(rita));

            assertThat(access.require(AuthPrincipal.user(rita.getId()))).isSameAs(rita);
        }
    }

    @Test
    void everyoneElseIsTurnedAway() {
        StaffAccess access = new StaffAccess(users);
        User ada = user("Ada");
        when(users.findById(ada.getId())).thenReturn(Optional.of(ada));
        UUID gone = UUID.randomUUID();
        when(users.findById(gone)).thenReturn(Optional.empty());

        assertApiError(() -> access.require(AuthPrincipal.user(ada.getId())), HttpStatus.FORBIDDEN, "STAFF_ONLY");
        assertApiError(() -> access.require(AuthPrincipal.user(gone)), HttpStatus.FORBIDDEN, "STAFF_ONLY");
    }

    @Test
    void guestsAndNobodyAreTurnedAwayWithoutALookup() {
        StaffAccess access = new StaffAccess(users);

        assertApiError(() -> access.require(AuthPrincipal.guest(UUID.randomUUID(), UUID.randomUUID())),
            HttpStatus.FORBIDDEN, "STAFF_ONLY");
        assertApiError(() -> access.require(null), HttpStatus.FORBIDDEN, "STAFF_ONLY");
        verifyNoInteractions(users);
    }
}
