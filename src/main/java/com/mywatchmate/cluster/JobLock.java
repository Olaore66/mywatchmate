package com.mywatchmate.cluster;

import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Runs a background job on one copy of the app at a time, however many run: the copy that takes the job's row
 * in {@code job_locks} runs it, the others skip that turn. In the database we already have, so nothing new to
 * run, and it works the same with one copy. Times are the database's, so the copies' clocks don't matter.
 * <ul>
 *   <li>{@code atMost}: a copy that dies mid-job holds the lock no longer than this. Longer than the job takes.</li>
 *   <li>{@code atLeast}: held at least this long after starting, so another copy whose timer fires a moment
 *       later doesn't run the same turn again. Shorter than the job's interval.</li>
 * </ul>
 */
@Slf4j
@Component
public class JobLock {

    private final JdbcClient jdbc;
    private final String nodeId;

    public JobLock(JdbcClient jdbc, ClusterProperties cluster) {
        this.jdbc = jdbc;
        this.nodeId = cluster.nodeId();
    }

    /** @return true if this copy ran the job, false if another copy holds it */
    public boolean runExclusively(String name, Duration atMost, Duration atLeast, Runnable job) {
        int taken = jdbc.sql("""
                INSERT INTO job_locks (name, locked_until, locked_at, locked_by)
                VALUES (:name, now() + :atMost * interval '1 millisecond', now(), :by)
                ON CONFLICT (name) DO UPDATE
                    SET locked_until = EXCLUDED.locked_until, locked_at = EXCLUDED.locked_at, locked_by = EXCLUDED.locked_by
                    WHERE job_locks.locked_until <= now()
                """)
            .param("name", name).param("atMost", atMost.toMillis()).param("by", nodeId)
            .update();
        if (taken == 0) {
            log.debug("Job {} skipped: another copy holds it", name);
            return false;
        }
        try {
            job.run();
        } finally {
            jdbc.sql("""
                    UPDATE job_locks SET locked_until = greatest(now(), locked_at + :atLeast * interval '1 millisecond')
                    WHERE name = :name AND locked_by = :by
                    """)
                .param("name", name).param("atLeast", atLeast.toMillis()).param("by", nodeId)
                .update();
        }
        return true;
    }
}
