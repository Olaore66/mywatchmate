package com.mywatchmate.watch;

import static org.assertj.core.api.Assertions.assertThat;

import com.mywatchmate.identity.User;
import java.time.Instant;
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
    void displayNameComesFromTheAccountOrTheGuestName() {
        assertThat(Participant.member(space, owner, Participant.Role.OWNER).displayName()).isEqualTo("Owner");
        assertThat(Participant.guest(space, "Bisi", "h").displayName()).isEqualTo("Bisi");
    }

    @Test
    void leavingAndRejoiningResetsTheRole() {
        Participant admin = Participant.member(space, new User("t@example.com", "Tunde"), Participant.Role.ADMIN);
        Instant left = Instant.parse("2026-10-22T06:00:00Z");
        admin.leave(left);
        admin.leave(left.plusSeconds(30));
        assertThat(admin.isActive()).isFalse();
        assertThat(admin.getLeftAt()).isEqualTo(left);
        assertThat(admin.wasRemoved()).isFalse();

        Instant back = left.plusSeconds(3600);
        admin.rejoin(back);
        assertThat(admin.isActive()).isTrue();
        assertThat(admin.getRole()).isEqualTo(Participant.Role.MEMBER);
        assertThat(admin.getJoinedAt()).isEqualTo(back);
    }

    @Test
    void removingRecordsWhoAndWhen() {
        Participant remover = Participant.member(space, owner, Participant.Role.OWNER);
        Participant bisi = Participant.guest(space, "Bisi", "h");
        Instant now = Instant.parse("2026-10-22T06:00:00Z");

        bisi.remove(now, remover);

        assertThat(bisi.isActive()).isFalse();
        assertThat(bisi.wasRemoved()).isTrue();
        assertThat(bisi.getRemovedAt()).isEqualTo(now);
        assertThat(bisi.getRemovedBy()).isSameAs(remover);
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
