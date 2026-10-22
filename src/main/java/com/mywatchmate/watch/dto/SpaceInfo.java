package com.mywatchmate.watch.dto;

import com.mywatchmate.watch.Space;
import java.util.UUID;

/** The room's shared settings, as sent in SPACE_UPDATED / SPACE_ARCHIVED / SPACE_RESTORED events. */
public record SpaceInfo(UUID id, String title, String description, Space.AudienceType audienceType,
                        boolean allowGuests, boolean membersCanAddContent, boolean archived) {

    public static SpaceInfo from(Space space) {
        return new SpaceInfo(space.getId(), space.getTitle(), space.getDescription(), space.getAudienceType(),
            space.isAllowGuests(), space.isMembersCanAddContent(), space.isArchived());
    }
}
