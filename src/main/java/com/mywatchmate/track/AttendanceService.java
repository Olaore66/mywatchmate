package com.mywatchmate.track;

import com.mywatchmate.auth.AuthPrincipal;
import com.mywatchmate.common.ApiException;
import com.mywatchmate.live.PresenceTracker;
import com.mywatchmate.live.dto.OnlineViewer;
import com.mywatchmate.sessions.dto.SessionResponse;
import com.mywatchmate.track.dto.AttendanceFinalised;
import com.mywatchmate.track.dto.AttendeeResponse;
import com.mywatchmate.track.dto.MyAttendanceResponse;
import com.mywatchmate.track.dto.RoomAttendanceResponse;
import com.mywatchmate.track.dto.RoomAttendanceResponse.MemberSummary;
import com.mywatchmate.track.dto.RoomAttendanceResponse.SessionSummary;
import com.mywatchmate.track.dto.SessionAttendanceResponse;
import com.mywatchmate.watch.Participant;
import com.mywatchmate.watch.ParticipantRepository;
import com.mywatchmate.watch.SpaceAccess;
import com.mywatchmate.watch.SpaceEvent;
import com.mywatchmate.watch.SpaceEvents;
import com.mywatchmate.watch.WatchSession;
import com.mywatchmate.watch.WatchSessionRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Attendance comes from presence: while a session is live, everyone with the room open builds up
 * watch time. When it ends, each person counts if they watched enough of it (half, at most 20 minutes),
 * the room hears "8 of 10 attended", and the streaks of everyone involved are rebuilt.
 */
@Service
@RequiredArgsConstructor
public class AttendanceService {

    static final int DRIFT_AFTER_MISSES = 3;
    private static final Duration DEFAULT_RANGE = Duration.ofDays(30);
    private static final Duration MAX_RANGE = Duration.ofDays(366);

    private final AttendanceRepository attendance;
    private final WatchSessionRepository sessions;
    private final ParticipantRepository participants;
    private final PresenceTracker presence;
    private final SpaceAccess access;
    private final SpaceEvents events;
    private final StreakService streaks;
    private final TrackProperties props;
    private final Clock clock;

    /** Credits watch time to everyone online in the session's room right now. Call inside a transaction. */
    public void credit(WatchSession session, Instant now) {
        List<OnlineViewer> online = presence.online(session.getSpace().getId());
        if (online.isEmpty()) {
            return;
        }
        Map<UUID, Attendance> existing = attendance.findBySessionId(session.getId()).stream()
            .collect(Collectors.toMap(a -> a.getId().getParticipantId(), Function.identity()));
        for (OnlineViewer viewer : online) {
            Attendance row = existing.get(viewer.participantId());
            if (row == null) {
                attendance.save(new Attendance(session, participants.getReferenceById(viewer.participantId()), now));
            } else {
                row.seen(now, props.tickCap());
            }
        }
    }

    @EventListener
    public void onSpaceEvent(SpaceEvent event) {
        if (event.type() == SpaceEvent.Type.SESSION_ENDED && event.data() instanceof SessionResponse ended) {
            sessions.findById(ended.id()).filter(s -> s.getStartedAt() != null).ifPresent(this::finalise);
        }
    }

    /** The session just ended (in this transaction): decide who counted and rebuild their streaks. */
    public void finalise(WatchSession session) {
        credit(session, session.getEndedAt());
        int required = requiredSeconds(session);
        List<Attendance> rows = attendance.findForSession(session.getId());
        rows.forEach(a -> a.finish(required));

        UUID spaceId = session.getSpace().getId();
        // Expected (members when it started) plus anyone who joined later and still counted
        Map<UUID, Participant> involved = new LinkedHashMap<>();
        participants.findAllWithUsers(spaceId).stream()
            .filter(p -> wasMemberAt(p, session.getStartedAt()))
            .forEach(p -> involved.put(p.getId(), p));
        rows.stream().filter(Attendance::isCounted).map(Attendance::getParticipant)
            .forEach(p -> involved.putIfAbsent(p.getId(), p));
        int attended = (int) rows.stream().filter(Attendance::isCounted).count();
        events.publish(spaceId, SpaceEvent.Type.ATTENDANCE_FINALISED,
            new AttendanceFinalised(session.getId(), attended, involved.size()));

        involved.values().stream().filter(p -> !p.isGuest()).map(p -> p.getUser().getId()).distinct()
            .forEach(streaks::recompute);
    }

    @Transactional(readOnly = true)
    public SessionAttendanceResponse forSession(AuthPrincipal principal, UUID spaceId, UUID sessionId) {
        access.require(principal, spaceId);
        WatchSession session = sessions.findByIdAndSpaceId(sessionId, spaceId)
            .orElseThrow(() -> ApiException.notFound("SESSION_NOT_FOUND", "That session isn't in this room."));
        boolean decided = session.getStatus() == WatchSession.Status.ENDED;
        List<AttendeeResponse> people = attendance.findForSession(sessionId).stream()
            .map(a -> AttendeeResponse.from(a, decided))
            .toList();
        int attended = (int) people.stream().filter(p -> Boolean.TRUE.equals(p.counted())).count();
        return new SessionAttendanceResponse(sessionId, session.getStatus(), session.getStartedAt(), session.getEndedAt(),
            decided && session.getStartedAt() != null ? requiredSeconds(session) : null, attended, people.size(), people);
    }

    /** Hosts' view of a period: how each session went and who has been drifting away. */
    @Transactional(readOnly = true)
    public RoomAttendanceResponse forRoom(AuthPrincipal principal, UUID spaceId, Instant from, Instant to) {
        access.require(principal, spaceId).requireOwnerOrAdmin();
        Instant end = to != null ? to : clock.instant();
        Instant start = from != null ? from : end.minus(DEFAULT_RANGE);
        if (!start.isBefore(end) || Duration.between(start, end).compareTo(MAX_RANGE) > 0) {
            throw ApiException.badRequest("INVALID_RANGE", "'from' must be before 'to', and at most a year apart.");
        }

        List<WatchSession> held = sessions.findStartedBetween(spaceId, start, end).stream()
            .filter(s -> s.getStatus() == WatchSession.Status.ENDED)
            .toList();
        Set<String> counted = new HashSet<>();
        if (!held.isEmpty()) {
            attendance.findForSessions(held.stream().map(WatchSession::getId).toList()).stream()
                .filter(Attendance::isCounted)
                .forEach(a -> counted.add(key(a.getId().getSessionId(), a.getId().getParticipantId())));
        }
        List<Participant> everyone = participants.findAllWithUsers(spaceId);

        List<SessionSummary> sessionSummaries = held.stream().map(s -> {
            Set<UUID> expected = new HashSet<>();
            int attended = 0;
            for (Participant p : everyone) {
                boolean came = counted.contains(key(s.getId(), p.getId()));
                if (came || wasMemberAt(p, s.getStartedAt())) {
                    expected.add(p.getId());
                }
                if (came) {
                    attended++;
                }
            }
            return new SessionSummary(s.getId(), s.getTitle(), s.getStartedAt(), attended, expected.size());
        }).toList();

        List<MemberSummary> members = new ArrayList<>();
        for (Participant p : everyone) {
            if (!p.isActive()) {
                continue;
            }
            int attended = 0;
            int expected = 0;
            int missedInARow = 0;
            Instant lastAttended = null;
            for (WatchSession s : held) {
                boolean came = counted.contains(key(s.getId(), p.getId()));
                if (!came && !wasMemberAt(p, s.getStartedAt())) {
                    continue;
                }
                expected++;
                if (came) {
                    attended++;
                    lastAttended = s.getStartedAt();
                    missedInARow = 0;
                } else {
                    missedInARow++;
                }
            }
            members.add(new MemberSummary(p.getId(), p.displayName(), p.isGuest(), attended, expected, lastAttended,
                missedInARow >= DRIFT_AFTER_MISSES));
        }
        return new RoomAttendanceResponse(start, end, sessionSummaries, members);
    }

    @Transactional(readOnly = true)
    public List<MyAttendanceResponse> mine(UUID userId, Instant from, Instant to) {
        Instant end = to != null ? to : clock.instant().plus(Duration.ofDays(1));
        Instant start = from != null ? from : end.minus(DEFAULT_RANGE);
        if (!start.isBefore(end)) {
            throw ApiException.badRequest("INVALID_RANGE", "'from' must be before 'to'.");
        }
        return attendance.findHistory(userId, start, end).stream().map(MyAttendanceResponse::from).toList();
    }

    int requiredSeconds(WatchSession session) {
        Instant end = session.getEndedAt() != null ? session.getEndedAt() : clock.instant();
        return props.requiredSeconds(Duration.between(session.getStartedAt(), end));
    }

    /** In the room when the session started (joined before it, and hadn't left yet). */
    static boolean wasMemberAt(Participant p, Instant moment) {
        return !p.getJoinedAt().isAfter(moment) && (p.getLeftAt() == null || p.getLeftAt().isAfter(moment));
    }

    private static String key(UUID sessionId, UUID participantId) {
        return sessionId + ":" + participantId;
    }
}
