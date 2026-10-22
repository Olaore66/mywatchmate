package com.mywatchmate.watch;

import com.mywatchmate.auth.AuthPrincipal;
import com.mywatchmate.common.ApiException;
import com.mywatchmate.identity.User;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Small builders for room unit tests: entities with ids, no database. */
final class WatchFixtures {

    private WatchFixtures() {
    }

    static User user(String name) {
        User user = new User(name.toLowerCase() + "@example.com", name);
        user.setId(UUID.randomUUID());
        return user;
    }

    static Space space(User owner) {
        Space space = new Space(owner, "6am Prayer Room", "invite-token-1", Space.AudienceType.DIASPORA);
        space.setId(UUID.randomUUID());
        return space;
    }

    static Participant member(Space space, User user, Participant.Role role) {
        Participant participant = Participant.member(space, user, role);
        participant.setId(UUID.randomUUID());
        return participant;
    }

    static Participant guest(Space space, String name) {
        Participant participant = Participant.guest(space, name, "hash-" + name);
        participant.setId(UUID.randomUUID());
        return participant;
    }

    static SpaceAccess.Membership membership(Participant participant) {
        return new SpaceAccess.Membership(participant.getSpace(), participant, SpaceAccess.levelOf(participant));
    }

    static AuthPrincipal principalOf(Participant participant) {
        return participant.isGuest()
            ? AuthPrincipal.guest(participant.getId(), participant.getSpace().getId())
            : AuthPrincipal.user(participant.getUser().getId());
    }

    static void assertApiError(ThrowingCallable call, HttpStatus status, String code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.getStatus()).isEqualTo(status);
            assertThat(e.getCode()).isEqualTo(code);
        });
    }
}
