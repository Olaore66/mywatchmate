package com.mywatchmate.watch;

import com.mywatchmate.identity.User;
import com.mywatchmate.watch.dto.ContentResponse;
import com.mywatchmate.watch.dto.PersonRef;
import com.mywatchmate.watch.dto.SpaceResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Builds the caller-specific view of a room. Call inside a transaction. */
@Component
@RequiredArgsConstructor
public class SpaceViews {

    private final ParticipantRepository participants;
    private final SpaceProperties props;

    public SpaceResponse of(SpaceAccess.Membership membership) {
        Space space = membership.space();
        // Counting first also flushes pending inserts, so timestamps below are populated
        long memberCount = participants.countBySpaceIdAndLeftAtIsNull(space.getId());
        User owner = space.getOwner();
        ContentItem playing = space.getCurrentContent();
        return new SpaceResponse(space.getId(), space.getTitle(), space.getDescription(), space.getAudienceType(),
            space.isAllowGuests(), space.isMembersCanAddContent(), new PersonRef(owner.getId(), owner.getDisplayName()),
            membership.level().name(), membership.participant().getId(), membership.canAddContent(),
            memberCount, props.maxMembers(), playing == null ? null : ContentResponse.from(playing),
            space.getArchivedAt(), space.getCreatedAt());
    }
}
