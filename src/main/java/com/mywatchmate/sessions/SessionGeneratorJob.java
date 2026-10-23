package com.mywatchmate.sessions;

import com.mywatchmate.watch.Schedule;
import com.mywatchmate.watch.ScheduleRepository;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Keeps every active schedule's sessions planned {@code generateDays} ahead, so a new day
 * appears at the end of the window as each day passes. One transaction per schedule: one
 * bad schedule can't stop the rest.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SessionGeneratorJob {

    private final ScheduleRepository schedules;
    private final SessionPlanner planner;
    private final TransactionTemplate tx;

    @Scheduled(initialDelayString = "PT10S", fixedDelayString = "${mywatchmate.sessions.generate-interval:PT1H}")
    public int run() {
        List<UUID> active = tx.execute(status -> schedules.findByActiveTrue().stream().map(Schedule::getId).toList());
        int created = 0;
        for (UUID id : active) {
            try {
                Integer count = tx.execute(status -> schedules.findById(id).map(planner::plan).orElse(0));
                created += count == null ? 0 : count;
            } catch (DataIntegrityViolationException e) {
                log.debug("Schedule {} was planned concurrently elsewhere", id);
            } catch (RuntimeException e) {
                log.warn("Couldn't plan sessions for schedule {}: {}", id, e.getMessage());
            }
        }
        if (created > 0) {
            log.info("Planned {} new sessions", created);
        }
        return created;
    }
}
