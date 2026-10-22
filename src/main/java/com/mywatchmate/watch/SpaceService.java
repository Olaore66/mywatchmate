package com.mywatchmate.watch;

import com.mywatchmate.auth.AuthPrincipal;
import com.mywatchmate.common.ApiException;
import com.mywatchmate.identity.User;
import com.mywatchmate.identity.UserRepository;
import com.mywatchmate.watch.SpaceAccess.Level;
import com.mywatchmate.watch.SpaceAccess.Membership;
import com.mywatchmate.watch.content.PreparedVideo;
import com.mywatchmate.watch.dto.CreateSpaceRequest;
import com.mywatchmate.watch.dto.InviteResponse;
import com.mywatchmate.watch.dto.SpaceInfo;
import com.mywatchmate.watch.dto.SpaceResponse;
import com.mywatchmate.watch.dto.SpaceSummaryResponse;
import com.mywatchmate.watch.dto.UpdateSpaceRequest;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** Rooms themselves: create, view, edit, archive/restore, invite links. */
@Service
@RequiredArgsConstructor
public class SpaceService {

    private final UserRepository users;
    private final SpaceRepository spaces;
    private final ParticipantRepository participants;
    private final SpaceAccess access;
    private final SpaceViews views;
    private final InviteTokens inviteTokens;
    private final ContentService content;
    private final SpaceEvents events;
    private final TransactionTemplate tx;

    /** The creator becomes the owner. An optional first video starts playing straight away. */
    public SpaceResponse create(UUID userId, CreateSpaceRequest request) {
        // Look the video up before opening a transaction: it's a network call
        PreparedVideo video = hasText(request.videoUrl()) ? content.prepare(request.videoUrl()) : null;
        return tx.execute(status -> {
            User owner = users.findById(userId)
                .orElseThrow(() -> ApiException.notFound("USER_NOT_FOUND", "This account no longer exists."));
            Space space = new Space(owner, request.title().trim(), inviteTokens.newToken(), request.audienceType());
            space.setDescription(blankToNull(request.description()));
            space.setAllowGuests(request.allowGuests() == null || request.allowGuests());
            space.setMembersCanAddContent(Boolean.TRUE.equals(request.membersCanAddContent()));
            spaces.save(space);

            Participant ownerRow = participants.save(Participant.member(space, owner, Participant.Role.OWNER));
            Membership membership = new Membership(space, ownerRow, Level.OWNER);
            if (video != null) {
                space.playNow(content.save(membership, video));
            }
            return views.of(membership);
        });
    }

    @Transactional(readOnly = true)
    public List<SpaceSummaryResponse> listMine(UUID userId, boolean archived) {
        return spaces.findRoomsOf(userId, archived);
    }

    @Transactional(readOnly = true)
    public SpaceResponse get(AuthPrincipal principal, UUID spaceId) {
        return views.of(access.require(principal, spaceId));
    }

    @Transactional
    public SpaceResponse update(AuthPrincipal principal, UUID spaceId, UpdateSpaceRequest request) {
        Membership membership = access.require(principal, spaceId).requireOwnerOrAdmin().requireNotArchived();
        Space space = membership.space();
        if (request.title() != null) {
            space.setTitle(request.title().trim());
        }
        if (request.description() != null) {
            space.setDescription(blankToNull(request.description()));
        }
        if (request.audienceType() != null) {
            space.setAudienceType(request.audienceType());
        }
        if (request.allowGuests() != null) {
            space.setAllowGuests(request.allowGuests());
        }
        if (request.membersCanAddContent() != null) {
            space.setMembersCanAddContent(request.membersCanAddContent());
        }
        events.publish(spaceId, SpaceEvent.Type.SPACE_UPDATED, SpaceInfo.from(space));
        return views.of(membership);
    }

    /** Hides the room and kills its invite link. Nothing is deleted. */
    @Transactional
    public SpaceResponse archive(AuthPrincipal principal, UUID spaceId) {
        Membership membership = access.require(principal, spaceId).requireLevel(Level.OWNER);
        if (!membership.space().isArchived()) {
            membership.space().archive(Instant.now());
            events.publish(spaceId, SpaceEvent.Type.SPACE_ARCHIVED, SpaceInfo.from(membership.space()));
        }
        return views.of(membership);
    }

    @Transactional
    public SpaceResponse restore(AuthPrincipal principal, UUID spaceId) {
        Membership membership = access.require(principal, spaceId).requireLevel(Level.OWNER);
        if (membership.space().isArchived()) {
            membership.space().restore();
            events.publish(spaceId, SpaceEvent.Type.SPACE_RESTORED, SpaceInfo.from(membership.space()));
        }
        return views.of(membership);
    }

    /** Any registered member can share the link. */
    @Transactional(readOnly = true)
    public InviteResponse invite(AuthPrincipal principal, UUID spaceId) {
        Membership membership = access.require(principal, spaceId)
            .requireLevel(Level.OWNER, Level.ADMIN, Level.MEMBER)
            .requireNotArchived();
        return inviteTokens.inviteFor(membership.space());
    }

    /** New link; the old one stops working at once (e.g. it leaked, or after removing someone). */
    @Transactional
    public InviteResponse rotateInvite(AuthPrincipal principal, UUID spaceId) {
        Membership membership = access.require(principal, spaceId).requireOwnerOrAdmin().requireNotArchived();
        membership.space().rotateInvite(inviteTokens.newToken());
        return inviteTokens.inviteFor(membership.space());
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String blankToNull(String value) {
        return hasText(value) ? value.trim() : null;
    }
}
