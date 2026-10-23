package com.mywatchmate.sessions;

import com.mywatchmate.notify.ReminderService;
import com.mywatchmate.watch.SpaceEvent;
import com.mywatchmate.watch.SpaceEvents;
import com.mywatchmate.watch.WatchSession;
import com.mywatchmate.watch.WatchSessionRepository;
import java.time.Clock;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Starts sessions when their time comes (so the room is live when people arrive) and ends them
 * when the video is over, or after the maximum length when its length is unknown.
 * <ul>
 *   <li>Another session already live in the room: the due one waits; it never interrupts.</li>
 *   <li>Its start passed more than the maximum length ago without starting (e.g. room archived,
 *       server down): it's marked missed (ENDED, never started).</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SessionLifecycleJob {

    static final int BATCH = 50;

    private final WatchSessionRepository sessions;
    private final SessionService sessionService;
    private final ReminderService reminders;
    private final SessionViews views;
    private final SpaceEvents events;
    private final SessionProperties props;
    private final TransactionTemplate tx;
    private final Clock clock;

    @Scheduled(initialDelayString = "PT15S", fixedDelayString = "${mywatchmate.sessions.lifecycle-interval:PT30S}")
    public void run() {
        startDue();
        endFinished();
    }

    /** @return how many sessions went live */
    public int startDue() {
        Integer started = tx.execute(status -> {
            Instant now = clock.instant();
            int count = 0;
            for (WatchSession session : sessions.lockDueToStart(now, BATCH)) {
                if (now.isAfter(session.getScheduledAt().plus(props.maxLength()))) {
                    session.markMissed(now);
                    reminders.cancelForSession(session);
                } else if (!session.getSpace().isArchived()
                    && !sessions.existsBySpaceIdAndStatus(session.getSpace().getId(), WatchSession.Status.LIVE)) {
                    sessionService.goLive(session);
                    count++;
                }
            }
            return count;
        });
        return started == null ? 0 : started;
    }

    /** @return how many live sessions ended */
    public int endFinished() {
        Integer ended = tx.execute(status -> {
            Instant now = clock.instant();
            int count = 0;
            for (WatchSession session : sessions.findByStatus(WatchSession.Status.LIVE)) {
                if (!now.isBefore(session.autoEndAt(props.maxLength(), props.endGrace()))) {
                    session.end(now);
                    events.publish(session.getSpace().getId(), SpaceEvent.Type.SESSION_ENDED, views.session(session));
                    count++;
                }
            }
            return count;
        });
        return ended == null ? 0 : ended;
    }
}
