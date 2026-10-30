package com.mywatchmate.invite;

import static com.mywatchmate.testsupport.Fixtures.MONDAY_MIDNIGHT;
import static com.mywatchmate.testsupport.Fixtures.member;
import static com.mywatchmate.testsupport.Fixtures.space;
import static com.mywatchmate.testsupport.Fixtures.user;
import static org.assertj.core.api.Assertions.assertThat;

import com.mywatchmate.invite.SpaceInvitation.Status;
import com.mywatchmate.invite.dto.InvitationResponse;
import com.mywatchmate.watch.Participant;
import com.mywatchmate.watch.Space;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SpaceInvitationTest {

    private static final Duration LIFETIME = Duration.ofDays(14);
    private static final Duration RETRY = Duration.ofMinutes(1);

    private Space space;
    private Participant ada;
    private SpaceInvitation invitation;

    @BeforeEach
    void setUp() {
        space = space(user("Ada"));
        ada = member(space, space.getOwner(), Participant.Role.OWNER);
        invitation = new SpaceInvitation(space, "kemi@example.com", ada, "See you Sunday", MONDAY_MIDNIGHT, LIFETIME);
    }

    private static Instant at(long seconds) {
        return MONDAY_MIDNIGHT.plusSeconds(seconds);
    }

    @Test
    void aNewInvitationIsQueuedToSendNow() {
        assertThat(invitation.getStatus()).isEqualTo(Status.PENDING);
        assertThat(invitation.getNextAttemptAt()).isEqualTo(MONDAY_MIDNIGHT);
        assertThat(invitation.getLastQueuedAt()).isEqualTo(MONDAY_MIDNIGHT);
        assertThat(invitation.getCreatedAt()).isEqualTo(MONDAY_MIDNIGHT);
        assertThat(invitation.getExpiresAt()).isEqualTo(MONDAY_MIDNIGHT.plus(LIFETIME));
        assertThat(invitation.isLive(at(1))).isTrue();
    }

    @Test
    void sendingThenJoining() {
        invitation.sending(at(30));
        assertThat(invitation.getStatus()).isEqualTo(Status.SENT);
        assertThat(invitation.getSentAt()).isEqualTo(at(30));
        assertThat(invitation.getAttempts()).isEqualTo(1);
        assertThat(invitation.getNextAttemptAt()).isNull();
        assertThat(invitation.isLive(at(31))).isTrue();

        invitation.joined(at(600));
        assertThat(invitation.getStatus()).isEqualTo(Status.JOINED);
        assertThat(invitation.getJoinedAt()).isEqualTo(at(600));
        assertThat(invitation.isLive(at(601))).isFalse();
    }

    @Test
    void aFailedSendIsRetriedLaterThenGivenUp() {
        invitation.sending(at(0));
        invitation.sendFailed("Connection refused", at(1), 3, RETRY);
        assertThat(invitation.getStatus()).isEqualTo(Status.PENDING);
        assertThat(invitation.getNextAttemptAt()).isEqualTo(at(61));
        assertThat(invitation.getSentAt()).isNull();
        assertThat(invitation.getLastError()).isEqualTo("Connection refused");

        invitation.sending(at(61));
        invitation.sendFailed("Connection refused", at(62), 3, RETRY);
        assertThat(invitation.getNextAttemptAt()).as("waits longer each time").isEqualTo(at(182));

        invitation.sending(at(182));
        invitation.sendFailed(null, at(183), 3, RETRY);
        assertThat(invitation.getStatus()).isEqualTo(Status.FAILED);
        assertThat(invitation.getNextAttemptAt()).isNull();
        assertThat(invitation.getLastError()).isEqualTo("unknown error");
    }

    @Test
    void longErrorsAreCut() {
        invitation.sending(at(0));
        invitation.sendFailed("x".repeat(1000), at(1), 3, RETRY);
        assertThat(invitation.getLastError()).hasSize(SpaceInvitation.MAX_ERROR);
    }

    @Test
    void requeuingStartsAgainWithAFreshExpiry() {
        invitation.sending(at(0));
        invitation.sendFailed("x", at(1), 1, RETRY);
        assertThat(invitation.getStatus()).isEqualTo(Status.FAILED);

        invitation.queue(at(3600), LIFETIME);

        assertThat(invitation.getStatus()).isEqualTo(Status.PENDING);
        assertThat(invitation.getAttempts()).isZero();
        assertThat(invitation.getLastError()).isNull();
        assertThat(invitation.getLastQueuedAt()).isEqualTo(at(3600));
        assertThat(invitation.getExpiresAt()).isEqualTo(at(3600).plus(LIFETIME));
        assertThat(invitation.getCreatedAt()).as("unchanged").isEqualTo(MONDAY_MIDNIGHT);
    }

    @Test
    void pastItsExpiryItShowsAsExpired() {
        Instant later = MONDAY_MIDNIGHT.plus(LIFETIME);
        assertThat(invitation.isLive(later)).isFalse();
        assertThat(invitation.statusAt(later)).isEqualTo(Status.EXPIRED);
        assertThat(invitation.statusAt(at(1))).isEqualTo(Status.PENDING);

        invitation.joined(at(5));
        assertThat(invitation.statusAt(later)).as("a joined one stays joined").isEqualTo(Status.JOINED);
    }

    @Test
    void cancellingAndExpiring() {
        invitation.cancel();
        assertThat(invitation.getStatus()).isEqualTo(Status.CANCELLED);
        assertThat(invitation.getNextAttemptAt()).isNull();

        SpaceInvitation other = new SpaceInvitation(space, "x@example.com", null, null, MONDAY_MIDNIGHT, LIFETIME);
        other.expire();
        assertThat(other.getStatus()).isEqualTo(Status.EXPIRED);
    }

    @Test
    void theListShowsWhoInvitedAndTheShownStatus() {
        InvitationResponse shown = InvitationResponse.from(invitation, MONDAY_MIDNIGHT.plus(LIFETIME));

        assertThat(shown.email()).isEqualTo("kemi@example.com");
        assertThat(shown.status()).isEqualTo(Status.EXPIRED);
        assertThat(shown.invitedBy().displayName()).isEqualTo("Ada");
        assertThat(shown.expiresAt()).isEqualTo(MONDAY_MIDNIGHT.plus(LIFETIME));

        SpaceInvitation byNobody = new SpaceInvitation(space, "x@example.com", null, null, MONDAY_MIDNIGHT, LIFETIME);
        assertThat(InvitationResponse.from(byNobody, MONDAY_MIDNIGHT).invitedBy()).isNull();
    }
}
