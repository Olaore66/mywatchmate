package com.mywatchmate.watch;

import static org.assertj.core.api.Assertions.assertThat;

import com.mywatchmate.identity.User;
import org.junit.jupiter.api.Test;

class ParticipantTest {

    private final User owner = new User("owner@example.com", "Owner");
    private final Space space = new Space(owner, "6am prayer", "inv-1", Space.AudienceType.DIASPORA);

    @Test
    void guestFactoryCreatesAMemberWithoutAccount() {
        Participant guest = Participant.guest(space, "Bisi", "hash");

        assertThat(guest.isGuest()).isTrue();
        assertThat(guest.getUser()).isNull();
        assertThat(guest.getGuestDisplayName()).isEqualTo("Bisi");
        assertThat(guest.getGuestTokenHash()).isEqualTo("hash");
        assertThat(guest.getRole()).isEqualTo(Participant.Role.MEMBER);
        assertThat(guest.getJoinedAt()).isNotNull();
    }

    @Test
    void memberFactoryLinksTheUserWithTheGivenRole() {
        Participant member = Participant.member(space, owner, Participant.Role.OWNER);

        assertThat(member.isGuest()).isFalse();
        assertThat(member.getUser()).isSameAs(owner);
        assertThat(member.getRole()).isEqualTo(Participant.Role.OWNER);
    }

    @Test
    void attachingToAUserClearsGuestIdentityButKeepsTheRow() {
        Participant guest = Participant.guest(space, "Bisi", "hash");
        User bisi = new User("bisi@example.com", "Bisi");

        guest.attachToUser(bisi);

        assertThat(guest.isGuest()).isFalse();
        assertThat(guest.getUser()).isSameAs(bisi);
        assertThat(guest.getGuestDisplayName()).isNull();
        assertThat(guest.getGuestTokenHash()).isNull();
        assertThat(guest.getSpace()).isSameAs(space);
    }
}
