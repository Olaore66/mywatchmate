package com.mywatchmate.watch;

import static com.mywatchmate.watch.WatchFixtures.assertApiError;
import static com.mywatchmate.watch.WatchFixtures.guest;
import static com.mywatchmate.watch.WatchFixtures.member;
import static com.mywatchmate.watch.WatchFixtures.membership;
import static com.mywatchmate.watch.WatchFixtures.space;
import static com.mywatchmate.watch.WatchFixtures.user;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.mywatchmate.auth.AuthPrincipal;
import com.mywatchmate.identity.User;
import com.mywatchmate.watch.SpaceAccess.Level;
import com.mywatchmate.watch.SpaceAccess.Membership;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

@ExtendWith(MockitoExtension.class)
class SpaceAccessTest {

    @Mock ParticipantRepository participants;

    private SpaceAccess access;
    private User ownerUser;
    private Space space;

    @BeforeEach
    void setUp() {
        access = new SpaceAccess(participants);
        ownerUser = user("Ada");
        space = space(ownerUser);
    }

    @Nested
    class FindingTheCaller {

        @Test
        void registeredMemberIsFoundWithTheirRoleAsLevel() {
            Participant admin = member(space, user("Tunde"), Participant.Role.ADMIN);
            when(participants.findBySpaceIdAndUserId(space.getId(), admin.getUser().getId())).thenReturn(Optional.of(admin));

            Membership membership = access.require(AuthPrincipal.user(admin.getUser().getId()), space.getId());

            assertThat(membership.level()).isEqualTo(Level.ADMIN);
            assertThat(membership.participant()).isSameAs(admin);
            assertThat(membership.space()).isSameAs(space);
        }

        @Test
        void guestIsFoundByParticipantIdAndHasGuestLevel() {
            Participant bisi = guest(space, "Bisi");
            when(participants.findById(bisi.getId())).thenReturn(Optional.of(bisi));

            Membership membership = access.require(AuthPrincipal.guest(bisi.getId(), space.getId()), space.getId());

            assertThat(membership.level()).isEqualTo(Level.GUEST);
        }

        @Test
        void outsiderGets404NotForbidden() {
            UUID stranger = UUID.randomUUID();
            when(participants.findBySpaceIdAndUserId(space.getId(), stranger)).thenReturn(Optional.empty());

            assertApiError(() -> access.require(AuthPrincipal.user(stranger), space.getId()),
                HttpStatus.NOT_FOUND, "SPACE_NOT_FOUND");
        }

        @Test
        void guestTokenForAnotherRoomIsRefused() {
            Participant bisi = guest(space, "Bisi");
            UUID otherRoom = UUID.randomUUID();
            when(participants.findById(bisi.getId())).thenReturn(Optional.of(bisi));

            assertApiError(() -> access.require(AuthPrincipal.guest(bisi.getId(), otherRoom), otherRoom),
                HttpStatus.NOT_FOUND, "SPACE_NOT_FOUND");
        }

        @Test
        void someoneWhoLeftIsNoLongerAMember() {
            Participant left = member(space, user("Tunde"), Participant.Role.MEMBER);
            left.leave(Instant.now());
            when(participants.findBySpaceIdAndUserId(space.getId(), left.getUser().getId())).thenReturn(Optional.of(left));

            assertThat(access.isActiveMember(AuthPrincipal.user(left.getUser().getId()), space.getId())).isFalse();
        }

        @Test
        void archivedRoomIsVisibleToItsOwnerOnly() {
            space.archive(Instant.now());
            Participant owner = member(space, ownerUser, Participant.Role.OWNER);
            Participant tunde = member(space, user("Tunde"), Participant.Role.MEMBER);
            when(participants.findBySpaceIdAndUserId(space.getId(), ownerUser.getId())).thenReturn(Optional.of(owner));
            when(participants.findBySpaceIdAndUserId(space.getId(), tunde.getUser().getId())).thenReturn(Optional.of(tunde));

            assertThat(access.require(AuthPrincipal.user(ownerUser.getId()), space.getId()).level()).isEqualTo(Level.OWNER);
            assertApiError(() -> access.require(AuthPrincipal.user(tunde.getUser().getId()), space.getId()),
                HttpStatus.NOT_FOUND, "SPACE_NOT_FOUND");
        }
    }

    @Nested
    class Permissions {

        /** The permission table from the plan, row by row. */
        @ParameterizedTest(name = "{0} owner={1} admin={2} member={3} guest={4}")
        @CsvSource({
            "editRoom,          true,  true,  false, false",
            "seeInviteLink,     true,  true,  true,  false",
            "ownerOnlyActions,  true,  false, false, false"
        })
        void permissionTable(String action, boolean owner, boolean admin, boolean member, boolean guest) {
            assertThat(allowed(action, levelMembership(Level.OWNER))).as("owner").isEqualTo(owner);
            assertThat(allowed(action, levelMembership(Level.ADMIN))).as("admin").isEqualTo(admin);
            assertThat(allowed(action, levelMembership(Level.MEMBER))).as("member").isEqualTo(member);
            assertThat(allowed(action, levelMembership(Level.GUEST))).as("guest").isEqualTo(guest);
        }

        @Test
        void addingVideosDependsOnTheRoomSetting() {
            space.setMembersCanAddContent(false);
            assertThat(levelMembership(Level.OWNER).canAddContent()).isTrue();
            assertThat(levelMembership(Level.ADMIN).canAddContent()).isTrue();
            assertThat(levelMembership(Level.MEMBER).canAddContent()).isFalse();
            assertThat(levelMembership(Level.GUEST).canAddContent()).isFalse();

            space.setMembersCanAddContent(true);
            assertThat(levelMembership(Level.MEMBER).canAddContent()).isTrue();
            assertThat(levelMembership(Level.GUEST).canAddContent()).isFalse();
        }

        @Test
        void wrongLevelIs403WithAHelpfulMessage() {
            assertApiError(() -> levelMembership(Level.ADMIN).requireLevel(Level.OWNER), HttpStatus.FORBIDDEN, "FORBIDDEN_ROLE");
            assertApiError(() -> levelMembership(Level.MEMBER).requireOwnerOrAdmin(), HttpStatus.FORBIDDEN, "FORBIDDEN_ROLE");
        }

        @Test
        void archivedRoomBlocksChanges() {
            space.archive(Instant.now());
            assertApiError(() -> levelMembership(Level.OWNER).requireNotArchived(), HttpStatus.CONFLICT, "SPACE_ARCHIVED");
        }

        private boolean allowed(String action, Membership membership) {
            try {
                switch (action) {
                    case "editRoom" -> membership.requireOwnerOrAdmin();
                    case "seeInviteLink" -> membership.requireLevel(Level.OWNER, Level.ADMIN, Level.MEMBER);
                    case "ownerOnlyActions" -> membership.requireLevel(Level.OWNER);
                    default -> throw new IllegalArgumentException(action);
                }
                return true;
            } catch (com.mywatchmate.common.ApiException e) {
                return false;
            }
        }

        private Membership levelMembership(Level level) {
            Participant participant = switch (level) {
                case OWNER -> member(space, ownerUser, Participant.Role.OWNER);
                case ADMIN -> member(space, user("Admin"), Participant.Role.ADMIN);
                case MEMBER -> member(space, user("Member"), Participant.Role.MEMBER);
                case GUEST -> guest(space, "Guest");
            };
            return membership(participant);
        }
    }
}
