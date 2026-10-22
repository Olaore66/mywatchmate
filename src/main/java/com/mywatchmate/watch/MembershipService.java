package com.mywatchmate.watch;

import com.mywatchmate.auth.AuthPrincipal;
import com.mywatchmate.common.ApiException;
import com.mywatchmate.identity.UserRepository;
import com.mywatchmate.watch.SpaceAccess.Level;
import com.mywatchmate.watch.SpaceAccess.Membership;
import com.mywatchmate.watch.dto.InvitePreviewResponse;
import com.mywatchmate.watch.dto.OwnershipTransferred;
import com.mywatchmate.watch.dto.ParticipantResponse;
import com.mywatchmate.watch.dto.SpaceResponse;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Who is in a room: joining, leaving, removing, roles and ownership. */
@Service
@RequiredArgsConstructor
public class MembershipService {

    private final SpaceRepository spaces;
    private final ParticipantRepository participants;
    private final UserRepository users;
    private final SpaceAccess access;
    private final SpaceViews views;
    private final SpaceEvents events;
    private final SpaceProperties props;

    /** Public: what the room behind a link looks like, before joining. */
    @Transactional(readOnly = true)
    public InvitePreviewResponse preview(String inviteToken) {
        Space space = activeSpaceByInvite(inviteToken);
        long members = participants.countBySpaceIdAndLeftAtIsNull(space.getId());
        ContentItem playing = space.getCurrentContent();
        return new InvitePreviewResponse(space.getTitle(), space.getDescription(), space.getAudienceType(),
            space.getOwner().getDisplayName(), members, members >= props.maxMembers(), space.isAllowGuests(),
            playing == null ? null : playing.getTitle(), playing == null ? null : playing.getThumbnailUrl());
    }

    /** A signed-in user joins from a link. Calling it again while already in the room is harmless. */
    @Transactional
    public SpaceResponse join(UUID userId, String inviteToken) {
        Space space = activeSpaceByInvite(inviteToken);
        Optional<Participant> existing = participants.findBySpaceIdAndUserId(space.getId(), userId);
        if (existing.isPresent() && existing.get().isActive()) {
            Participant member = existing.get();
            return views.of(new Membership(space, member, SpaceAccess.levelOf(member)));
        }
        if (existing.isPresent() && existing.get().wasRemoved()) {
            throw ApiException.forbidden("REMOVED_FROM_SPACE", "You were removed from this room.");
        }
        requireRoom(space);

        Participant member = existing
            .map(left -> {
                left.rejoin(Instant.now());
                return left;
            })
            .orElseGet(() -> participants.save(
                Participant.member(space, users.getReferenceById(userId), Participant.Role.MEMBER)));
        events.publish(space.getId(), SpaceEvent.Type.MEMBER_JOINED, ParticipantResponse.from(member));
        return views.of(new Membership(space, member, Level.MEMBER));
    }

    /** Used by POST /auth/guest: same link checks as users, plus "guests allowed". */
    @Transactional
    public Participant admitGuest(String inviteToken, String displayName, String deviceTokenHash) {
        Space space = activeSpaceByInvite(inviteToken);
        if (!space.isAllowGuests()) {
            throw ApiException.forbidden("GUESTS_NOT_ALLOWED", "This room requires an account to join.");
        }
        requireRoom(space);
        Participant guest = participants.save(Participant.guest(space, displayName, deviceTokenHash));
        events.publish(space.getId(), SpaceEvent.Type.MEMBER_JOINED, ParticipantResponse.from(guest));
        return guest;
    }

    @Transactional(readOnly = true)
    public List<ParticipantResponse> list(AuthPrincipal principal, UUID spaceId) {
        access.require(principal, spaceId);
        return participants.findActiveWithUsers(spaceId).stream().map(ParticipantResponse::from).toList();
    }

    /** Owner only: ADMIN ↔ MEMBER for registered members. */
    @Transactional
    public ParticipantResponse changeRole(AuthPrincipal principal, UUID spaceId, UUID targetId, Participant.Role role) {
        Membership owner = access.require(principal, spaceId).requireLevel(Level.OWNER).requireNotArchived();
        if (role == Participant.Role.OWNER) {
            throw ApiException.badRequest("INVALID_ROLE", "Use transfer-ownership to make someone the owner.");
        }
        Participant target = activeTarget(spaceId, targetId);
        if (target.getId().equals(owner.participant().getId())) {
            throw ApiException.badRequest("CANNOT_TARGET_SELF", "You can't change your own role.");
        }
        if (target.isGuest()) {
            throw ApiException.badRequest("GUEST_CANNOT_BE_ADMIN", "Guests need an account before they can become admins.");
        }
        target.changeRole(role);
        ParticipantResponse response = ParticipantResponse.from(target);
        events.publish(spaceId, SpaceEvent.Type.ROLE_CHANGED, response);
        return response;
    }

    /** Owner or admin. Admins can't remove the owner or other admins. Removed people can't rejoin from the link. */
    @Transactional
    public void remove(AuthPrincipal principal, UUID spaceId, UUID targetId) {
        Membership actor = access.require(principal, spaceId).requireOwnerOrAdmin().requireNotArchived();
        Participant target = activeTarget(spaceId, targetId);
        if (target.getId().equals(actor.participant().getId())) {
            throw ApiException.badRequest("CANNOT_TARGET_SELF", "Use leave to exit the room yourself.");
        }
        Level targetLevel = SpaceAccess.levelOf(target);
        if (targetLevel == Level.OWNER) {
            throw ApiException.forbidden("FORBIDDEN_ROLE", "The owner can't be removed.");
        }
        if (actor.level() == Level.ADMIN && targetLevel == Level.ADMIN) {
            throw ApiException.forbidden("FORBIDDEN_ROLE", "Admins can't remove other admins.");
        }
        target.remove(Instant.now(), actor.participant());
        events.publish(spaceId, SpaceEvent.Type.MEMBER_REMOVED, ParticipantResponse.from(target));
    }

    /** Anyone but the owner, who must hand the room over first. */
    @Transactional
    public void leave(AuthPrincipal principal, UUID spaceId) {
        Membership membership = access.require(principal, spaceId);
        if (membership.level() == Level.OWNER) {
            throw ApiException.conflict("OWNER_MUST_TRANSFER", "Transfer ownership to another member before leaving.");
        }
        membership.participant().leave(Instant.now());
        events.publish(spaceId, SpaceEvent.Type.MEMBER_LEFT, ParticipantResponse.from(membership.participant()));
    }

    /** Owner hands the room to a registered member, and becomes an admin. */
    @Transactional
    public SpaceResponse transferOwnership(AuthPrincipal principal, UUID spaceId, UUID targetId) {
        Membership owner = access.require(principal, spaceId).requireLevel(Level.OWNER).requireNotArchived();
        Participant target = activeTarget(spaceId, targetId);
        if (target.getId().equals(owner.participant().getId())) {
            throw ApiException.badRequest("CANNOT_TARGET_SELF", "You already own this room.");
        }
        if (target.isGuest()) {
            throw ApiException.badRequest("GUEST_CANNOT_BE_OWNER", "Guests need an account before they can own a room.");
        }
        target.changeRole(Participant.Role.OWNER);
        owner.participant().changeRole(Participant.Role.ADMIN);
        owner.space().setOwner(target.getUser());
        events.publish(spaceId, SpaceEvent.Type.OWNERSHIP_TRANSFERRED, new OwnershipTransferred(
            ParticipantResponse.from(owner.participant()), ParticipantResponse.from(target)));
        return views.of(new Membership(owner.space(), owner.participant(), Level.ADMIN));
    }

    private Space activeSpaceByInvite(String inviteToken) {
        return spaces.findByInviteToken(inviteToken)
            .filter(space -> !space.isArchived())
            .orElseThrow(() -> ApiException.notFound("INVITE_NOT_FOUND", "This invite link is invalid or no longer active."));
    }

    private void requireRoom(Space space) {
        if (participants.countBySpaceIdAndLeftAtIsNull(space.getId()) >= props.maxMembers()) {
            throw ApiException.conflict("SPACE_FULL", "This room is full.");
        }
    }

    private Participant activeTarget(UUID spaceId, UUID targetId) {
        return participants.findById(targetId)
            .filter(p -> p.getSpace().getId().equals(spaceId) && p.isActive())
            .orElseThrow(() -> ApiException.notFound("PARTICIPANT_NOT_FOUND", "That person isn't in this room."));
    }
}
