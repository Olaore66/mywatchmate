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

    /** A SEEK closer than this to where a playing room already is changes nothing. */
    static final long SEEK_TOLERANCE_MS = 1000;

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
        if (alreadyThere(state, command, now)) {
            return PlaybackResponse.of(state, now);
        }
        switch (command.action()) {
            case PLAY -> state.play(now, membership.participant());
            case PAUSE -> state.pause(now, membership.participant());
            case SEEK -> state.seek(seekTarget(state, command), now, membership.participant());
        }
        PlaybackResponse response = PlaybackResponse.of(state, now);
        events.publish(spaceId, SpaceEvent.Type.PLAYBACK_CHANGED, response);
        return response;
    }

    /**
     * A player tells us how long the video is, for sources whose length we can't look up (YouTube). From then on
     * the server knows when it ends: it stops at the end, PLAY starts it again, and a live session can finish.
     * Only the first report counts, and only for the video playing now.
     */
    @Transactional
    public PlaybackResponse reportDuration(AuthPrincipal principal, UUID spaceId, UUID contentId, long durationMs) {
        Membership membership = access.require(principal, spaceId).requireNotArchived();
        if (!membership.canControlPlayback()) {
            throw ApiException.forbidden("FORBIDDEN_ROLE", "Only people who can control playback can report the length.");
        }
        PlaybackState state = lock(spaceId);
        Instant now = clock.instant();
        ContentItem content = state.getContent();
        if (content != null && content.getId().equals(contentId) && !content.isLive() && content.getDurationSeconds() == null) {
            state.learnLength(content, (int) Math.ceilDiv(durationMs, 1000L), now);
            PlaybackResponse response = PlaybackResponse.of(state, now);
            events.publish(spaceId, SpaceEvent.Type.PLAYBACK_CHANGED, response);
            return response;
        }
        return PlaybackResponse.of(state, now);
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

    /**
     * PLAY while playing, or PAUSE while paused, changes nothing, so it must not look like a change. A player
     * reports "playing" every time it recovers from buffering; if that bumped the version, every viewer would
     * re-seek, buffer and report again, and the room would keep jumping back over the same few seconds.
     * Likewise a SEEK to about where a playing room already is: that's a player catching up, not someone jumping.
     */
    private static boolean alreadyThere(PlaybackState state, PlaybackCommand command, Instant now) {
        return switch (command.action()) {
            case PLAY -> state.statusAt(now) == PlaybackState.Status.PLAYING;
            case PAUSE -> state.getStatus() == PlaybackState.Status.PAUSED;
            case SEEK -> command.positionMs() != null && !state.getContent().isLive()
                && state.statusAt(now) == PlaybackState.Status.PLAYING
                && Math.abs(command.positionMs() - state.positionAt(now)) < SEEK_TOLERANCE_MS;
        };
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
