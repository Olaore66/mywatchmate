package com.mywatchmate.sessions;

import com.mywatchmate.auth.AuthPrincipal;
import com.mywatchmate.common.ApiException;
import com.mywatchmate.notify.ReminderService;
import com.mywatchmate.sessions.dto.CreateSessionRequest;
import com.mywatchmate.sessions.dto.SessionResponse;
import com.mywatchmate.sessions.dto.WatchNowRequest;
import com.mywatchmate.watch.ContentItem;
import com.mywatchmate.watch.ContentItemRepository;
import com.mywatchmate.watch.Space;
import com.mywatchmate.watch.SpaceAccess;
import com.mywatchmate.watch.SpaceAccess.Level;
import com.mywatchmate.watch.SpaceAccess.Membership;
import com.mywatchmate.watch.SpaceEvent;
import com.mywatchmate.watch.SpaceEvents;
import com.mywatchmate.watch.WatchSession;
import com.mywatchmate.watch.WatchSessionRepository;
import com.mywatchmate.watch.dto.ContentResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Sittings: listing, one-offs, "watch now", and manual start/end/cancel. Jobs reuse {@link #goLive}. */
@Service
@RequiredArgsConstructor
public class SessionService {

    static final Duration UPCOMING_FOR_USER = Duration.ofDays(7);

    private final WatchSessionRepository sessions;
    private final ContentItemRepository contentItems;
    private final SpaceAccess access;
    private final ReminderService reminders;
    private final SessionViews views;
    private final SpaceEvents events;
    private final SessionProperties props;
    private final Clock clock;

    /** Live and upcoming by default; with from/to, every session in that window (history). */
    @Transactional(readOnly = true)
    public List<SessionResponse> list(AuthPrincipal principal, UUID spaceId, Instant from, Instant to) {
        access.require(principal, spaceId);
        ZoneId zone = views.zoneFor(principal);
        List<WatchSession> found = (from == null && to == null)
            ? sessions.findUpcoming(spaceId, clock.instant().plus(Duration.ofDays(props.generateDays())),
                WatchSession.Status.LIVE, WatchSession.Status.SCHEDULED)
            : sessions.findBetween(spaceId, from == null ? Instant.EPOCH : from, to == null ? Instant.parse("9999-12-31T00:00:00Z") : to);
        return found.stream().map(s -> views.session(s, zone)).toList();
    }

    /** "What's coming up?" across all my rooms for the next 7 days, in my timezone. */
    @Transactional(readOnly = true)
    public List<SessionResponse> upcomingForUser(AuthPrincipal principal) {
        ZoneId zone = views.zoneFor(principal);
        Instant now = clock.instant();
        return sessions.findUpcomingForUser(principal.userId(), now, now.plus(UPCOMING_FOR_USER),
                WatchSession.Status.LIVE, WatchSession.Status.SCHEDULED)
            .stream().map(s -> views.session(s, zone)).toList();
    }

    @Transactional
    public SessionResponse createOneOff(AuthPrincipal principal, UUID spaceId, CreateSessionRequest request) {
        Membership host = access.require(principal, spaceId).requireOwnerOrAdmin().requireNotArchived();
        WatchSession session = new WatchSession(host.space(), contentFor(spaceId, request.contentId()), request.scheduledAt());
        session.setTitle(blankToNull(request.title()));
        sessions.save(session);
        reminders.scheduleFor(session);
        events.publish(spaceId, SpaceEvent.Type.SESSION_SCHEDULED, views.session(session));
        return views.session(session, views.zoneFor(principal));
    }

    /** Start watching together right now. Same rule as adding videos. */
    @Transactional
    public SessionResponse watchNow(AuthPrincipal principal, UUID spaceId, WatchNowRequest request) {
        Membership membership = access.require(principal, spaceId).requireNotArchived();
        if (!membership.canAddContent()) {
            throw ApiException.forbidden("FORBIDDEN_ROLE", membership.level() == Level.GUEST
                ? "Guests can't start sessions. Create an account to continue."
                : "Only the owner or admins can start a session in this room.");
        }
        requireNothingLive(spaceId);
        WatchSession session = new WatchSession(membership.space(),
            contentFor(spaceId, request == null ? null : request.contentId()), clock.instant());
        session.setTitle(request == null ? null : blankToNull(request.title()));
        sessions.save(session);
        goLive(session);
        return views.session(session, views.zoneFor(principal));
    }

    @Transactional
    public SessionResponse start(AuthPrincipal principal, UUID spaceId, UUID sessionId) {
        access.require(principal, spaceId).requireOwnerOrAdmin().requireNotArchived();
        WatchSession session = find(spaceId, sessionId);
        requireNothingLive(spaceId);
        goLive(session);
        return views.session(session, views.zoneFor(principal));
    }

    @Transactional
    public SessionResponse end(AuthPrincipal principal, UUID spaceId, UUID sessionId) {
        access.require(principal, spaceId).requireOwnerOrAdmin();
        WatchSession session = find(spaceId, sessionId);
        session.end(clock.instant());
        events.publish(spaceId, SpaceEvent.Type.SESSION_ENDED, views.session(session));
        return views.session(session, views.zoneFor(principal));
    }

    @Transactional
    public SessionResponse cancel(AuthPrincipal principal, UUID spaceId, UUID sessionId) {
        access.require(principal, spaceId).requireOwnerOrAdmin();
        WatchSession session = find(spaceId, sessionId);
        session.cancel();
        reminders.cancelForSession(session);
        events.publish(spaceId, SpaceEvent.Type.SESSION_CANCELLED, views.session(session));
        return views.session(session, views.zoneFor(principal));
    }

    /**
     * SCHEDULED → LIVE now. Plays the session's video for the room (or keeps whatever is playing if it has
     * none). Caller checks that nothing else is live. Call inside a transaction.
     */
    public void goLive(WatchSession session) {
        Space space = session.getSpace();
        session.start(clock.instant());
        if (session.getContentItem() == null) {
            session.setContentItem(space.getCurrentContent());
        } else if (space.getCurrentContent() == null || !space.getCurrentContent().getId().equals(session.getContentItem().getId())) {
            space.playNow(session.getContentItem());
            events.publish(space.getId(), SpaceEvent.Type.NOW_PLAYING_CHANGED, ContentResponse.from(session.getContentItem()));
        }
        events.publish(space.getId(), SpaceEvent.Type.SESSION_STARTED, views.session(session));
    }

    private void requireNothingLive(UUID spaceId) {
        if (sessions.existsBySpaceIdAndStatus(spaceId, WatchSession.Status.LIVE)) {
            throw ApiException.conflict("SESSION_ALREADY_LIVE", "A session is already live in this room. Join it, or end it first.");
        }
    }

    private WatchSession find(UUID spaceId, UUID sessionId) {
        return sessions.findByIdAndSpaceId(sessionId, spaceId)
            .orElseThrow(() -> ApiException.notFound("SESSION_NOT_FOUND", "That session isn't in this room."));
    }

    private ContentItem contentFor(UUID spaceId, UUID contentId) {
        if (contentId == null) {
            return null;
        }
        return contentItems.findByIdAndSpaceId(contentId, spaceId)
            .orElseThrow(() -> ApiException.notFound("CONTENT_NOT_FOUND", "That video isn't in this room."));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
