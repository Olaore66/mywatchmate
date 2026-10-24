package com.mywatchmate.live;

import com.mywatchmate.auth.AuthPrincipal;
import com.mywatchmate.common.ApiException;
import com.mywatchmate.live.dto.PlaybackCommand;
import com.mywatchmate.live.dto.PlaybackResponse;
import com.mywatchmate.watch.ContentItem;
import com.mywatchmate.watch.ContentItemRepository;
import com.mywatchmate.watch.Space;
import com.mywatchmate.watch.SpaceAccess;
import com.mywatchmate.watch.SpaceAccess.Level;
import com.mywatchmate.watch.SpaceAccess.Membership;
import com.mywatchmate.watch.SpaceEvent;
import com.mywatchmate.watch.SpaceEvents;
import com.mywatchmate.watch.dto.ContentResponse;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The room's shared play/pause/seek. A press is checked, applied to the saved state (version + 1)
 * and broadcast as PLAYBACK_CHANGED; every player then lines itself up with that state.
 * A new video (NOW_PLAYING_CHANGED) starts from the beginning for everyone.
 */
@Service
@RequiredArgsConstructor
public class PlaybackService {

    private final PlaybackStateRepository states;
    private final ContentItemRepository contentItems;
    private final SpaceAccess access;
    private final SpaceEvents events;
    private final CommandRateLimiter rateLimiter;
    private final Clock clock;

    @Transactional(readOnly = true)
    public PlaybackResponse get(AuthPrincipal principal, UUID spaceId) {
        return current(access.require(principal, spaceId).space());
    }

    /** The room's state right now. Call inside a transaction. */
    public PlaybackResponse current(Space space) {
        Instant now = clock.instant();
        PlaybackState state = states.findWithContent(space.getId())
            .orElseGet(() -> PlaybackState.initial(space.getId(), space.getCurrentContent(), now));
        return PlaybackResponse.of(state, now);
    }

    @Transactional
    public PlaybackResponse command(AuthPrincipal principal, UUID spaceId, PlaybackCommand command) {
        Membership membership = access.require(principal, spaceId).requireNotArchived();
        if (!membership.canControlPlayback()) {
            throw ApiException.forbidden("FORBIDDEN_ROLE", membership.level() == Level.GUEST
                ? "Guests can't control playback. Create an account to continue."
                : "Only the owner or admins can control playback in this room.");
        }
        rateLimiter.check(membership.participant().getId());

        PlaybackState state = lock(spaceId);
        if (state.getContent() == null) {
            throw ApiException.conflict("NOTHING_PLAYING", "Nothing is playing in this room. Add a video first.");
        }
        Instant now = clock.instant();
        switch (command.action()) {
            case PLAY -> state.play(now, membership.participant());
            case PAUSE -> state.pause(now, membership.participant());
            case SEEK -> state.seek(seekTarget(state, command), now, membership.participant());
        }
        PlaybackResponse response = PlaybackResponse.of(state, now);
        events.publish(spaceId, SpaceEvent.Type.PLAYBACK_CHANGED, response);
        return response;
    }

    /** A new video playing (or none): everyone starts it from the beginning. Runs in the change's transaction. */
    @EventListener
    public void onSpaceEvent(SpaceEvent event) {
        if (event.type() != SpaceEvent.Type.NOW_PLAYING_CHANGED) {
            return;
        }
        ContentItem content = event.data() instanceof ContentResponse playing
            ? contentItems.findById(playing.id()).orElse(null)
            : null;
        PlaybackState state = lock(event.spaceId());
        Instant now = clock.instant();
        state.load(content, now);
        events.publish(event.spaceId(), SpaceEvent.Type.PLAYBACK_CHANGED, PlaybackResponse.of(state, now));
    }

    private PlaybackState lock(UUID spaceId) {
        // A room created in this same transaction must be in the database before the insert can see it
        states.flush();
        states.insertIfMissing(spaceId);
        return states.findForUpdate(spaceId).orElseThrow(SpaceAccess::notFound);
    }

    private static long seekTarget(PlaybackState state, PlaybackCommand command) {
        if (command.positionMs() == null) {
            throw ApiException.badRequest("POSITION_REQUIRED", "Say where to jump to: positionMs.");
        }
        if (state.getContent().isLive()) {
            throw ApiException.unprocessable("LIVE_CANNOT_SEEK", "This is a live stream; everyone watches at the live point.");
        }
        Long length = state.lengthMs();
        if (length != null && command.positionMs() > length) {
            throw ApiException.unprocessable("POSITION_OUT_OF_RANGE", "That's past the end of the video.");
        }
        return command.positionMs();
    }
}
