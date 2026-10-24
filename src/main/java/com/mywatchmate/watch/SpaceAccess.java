package com.mywatchmate.watch;

import com.mywatchmate.auth.AuthPrincipal;
import com.mywatchmate.common.ApiException;
import com.mywatchmate.watch.dto.ParticipantResponse;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The single permission gate for rooms. Every room action starts here:
 * find the caller's ACTIVE participant row in the room, then check their level.
 * Not a member → 404 (we don't reveal the room exists); wrong level → 403.
 */
@Component
@RequiredArgsConstructor
public class SpaceAccess {

    public enum Level { OWNER, ADMIN, MEMBER, GUEST }

    private final ParticipantRepository participants;

    /** The caller's place in a room, and what that lets them do. */
    public record Membership(Space space, Participant participant, Level level) {

        public boolean isOwnerOrAdmin() {
            return level == Level.OWNER || level == Level.ADMIN;
        }

        public boolean canAddContent() {
            return isOwnerOrAdmin() || (level == Level.MEMBER && space.isMembersCanAddContent());
        }

        /** Guests never control playback. */
        public boolean canControlPlayback() {
            return isOwnerOrAdmin() || (level == Level.MEMBER && space.isMembersCanControlPlayback());
        }

        public Membership requireLevel(Level... allowed) {
            if (Arrays.asList(allowed).contains(level)) {
                return this;
            }
            throw ApiException.forbidden("FORBIDDEN_ROLE", describe(allowed));
        }

        public Membership requireOwnerOrAdmin() {
            return requireLevel(Level.OWNER, Level.ADMIN);
        }

        public Membership requireNotArchived() {
            if (space.isArchived()) {
                throw ApiException.conflict("SPACE_ARCHIVED", "This room is archived. Restore it first.");
            }
            return this;
        }

        private static String describe(Level... allowed) {
            if (allowed.length == 1 && allowed[0] == Level.OWNER) {
                return "Only the room owner can do this.";
            }
            if (Arrays.asList(allowed).contains(Level.ADMIN) && !Arrays.asList(allowed).contains(Level.MEMBER)) {
                return "Only the room owner or admins can do this.";
            }
            return "Guests can't do this. Create an account to continue.";
        }
    }

    /** The caller's membership, or 404 SPACE_NOT_FOUND. Archived rooms are visible to their owner only. */
    public Membership require(AuthPrincipal principal, UUID spaceId) {
        return find(principal, spaceId).orElseThrow(SpaceAccess::notFound);
    }

    /** For the WebSocket SUBSCRIBE check, which runs outside any request transaction. */
    @Transactional(readOnly = true)
    public boolean isActiveMember(AuthPrincipal principal, UUID spaceId) {
        return find(principal, spaceId).isPresent();
    }

    /** The caller's participant row in a room, if they're an active member. For presence (outside requests). */
    @Transactional(readOnly = true)
    public Optional<ParticipantResponse> activeParticipant(AuthPrincipal principal, UUID spaceId) {
        return find(principal, spaceId).map(m -> ParticipantResponse.from(m.participant()));
    }

    public static Level levelOf(Participant participant) {
        if (participant.isGuest()) {
            return Level.GUEST;
        }
        return switch (participant.getRole()) {
            case OWNER -> Level.OWNER;
            case ADMIN -> Level.ADMIN;
            case MEMBER -> Level.MEMBER;
        };
    }

    public static ApiException notFound() {
        return ApiException.notFound("SPACE_NOT_FOUND", "This room doesn't exist or you're not in it.");
    }

    private Optional<Membership> find(AuthPrincipal principal, UUID spaceId) {
        Optional<Participant> participant = principal.isGuest()
            ? participants.findById(principal.participantId())
                .filter(p -> p.isGuest() && p.getSpace().getId().equals(spaceId))
            : participants.findBySpaceIdAndUserId(spaceId, principal.userId());
        return participant
            .filter(Participant::isActive)
            .map(p -> new Membership(p.getSpace(), p, levelOf(p)))
            .filter(m -> !m.space().isArchived() || m.level() == Level.OWNER);
    }
}
