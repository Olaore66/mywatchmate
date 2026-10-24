package com.mywatchmate.watch;

import com.mywatchmate.connect.ChatService;
import com.mywatchmate.identity.User;
import com.mywatchmate.live.PlaybackService;
import com.mywatchmate.live.PresenceTracker;
import com.mywatchmate.track.StreakService;
import com.mywatchmate.watch.dto.ContentResponse;
import com.mywatchmate.watch.dto.PersonRef;
import com.mywatchmate.watch.dto.SpaceResponse;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Builds the caller-specific view of a room. Call inside a transaction. */
@Component
@RequiredArgsConstructor
public class SpaceViews {

    private final ParticipantRepository participants;
    private final SpaceProperties props;
    private final PlaybackService playback;
    private final PresenceTracker presence;
    private final StreakService streaks;
    private final ChatService chat;
    private final Clock clock;

    public SpaceResponse of(SpaceAccess.Membership membership) {
        Space space = membership.space();
        // Counting first also flushes pending inserts, so timestamps below are populated
        long memberCount = participants.countBySpaceIdAndLeftAtIsNull(space.getId());
        User owner = space.getOwner();
        ContentItem playing = space.getCurrentContent();
        boolean muted = membership.participant().isChatMuted(clock.instant());
        return new SpaceResponse(space.getId(), space.getTitle(), space.getDescription(), space.getAudienceType(),
            space.isAllowGuests(), space.isMembersCanAddContent(), space.isMembersCanControlPlayback(),
            new PersonRef(owner.getId(), owner.getDisplayName()), membership.level().name(),
            membership.participant().getId(), membership.canAddContent(), membership.canControlPlayback(),
            memberCount, props.maxMembers(), playing == null ? null : ContentResponse.from(playing),
            playback.current(space), presence.onlineCount(space.getId()), myStreak(membership),
            chat.unreadCount(membership.participant()),
            ChatService.shownUntil(muted ? membership.participant().getChatMutedUntil() : null), muted,
            space.getArchivedAt(), space.getCreatedAt());
    }

    private Integer myStreak(SpaceAccess.Membership membership) {
        User user = membership.participant().getUser();
        return user == null ? null : streaks.currentFor(user.getId());
    }
}
