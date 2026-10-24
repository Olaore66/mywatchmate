package com.mywatchmate.track;

import com.mywatchmate.watch.WatchSession;
import com.mywatchmate.watch.WatchSessionRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Every 30 seconds, credits watch time to everyone who has a live session's room open.
 * Live sessions are locked while being credited, so a second server skips them.
 */
@Component
@RequiredArgsConstructor
public class AttendanceJob {

    private final WatchSessionRepository sessions;
    private final AttendanceService attendance;
    private final TransactionTemplate tx;
    private final Clock clock;

    @Scheduled(initialDelayString = "PT20S", fixedDelayString = "${mywatchmate.track.attendance-interval:PT30S}")
    public void run() {
        tick();
    }

    /** @return how many live sessions were credited */
    public int tick() {
        Integer credited = tx.execute(status -> {
            Instant now = clock.instant();
            List<WatchSession> live = sessions.lockLive();
            live.forEach(session -> attendance.credit(session, now));
            return live.size();
        });
        return credited == null ? 0 : credited;
    }
}
