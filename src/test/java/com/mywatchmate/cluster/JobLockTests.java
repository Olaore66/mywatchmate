package com.mywatchmate.cluster;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

/** The single-copy job lock against the real database, with two "copies" of the app (each its own node id). */
@SpringBootTest
class JobLockTests {

    private static final Duration MINUTE = Duration.ofMinutes(1);

    @Autowired JdbcClient jdbc;

    private JobLock copy(String nodeId) {
        return new JobLock(jdbc, new ClusterProperties(ClusterProperties.Mode.LOCAL, nodeId, null));
    }

    private static String job() {
        return "test-" + UUID.randomUUID();
    }

    @Test
    void whileOneCopyRunsAJobAnotherSkipsIt() throws Exception {
        JobLock a = copy("a");
        JobLock b = copy("b");
        String job = job();
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Boolean> first = pool.submit(() -> a.runExclusively(job, MINUTE, Duration.ZERO, () -> {
                running.countDown();
                await(finish);
            }));
            assertThat(running.await(5, TimeUnit.SECONDS)).isTrue();

            AtomicInteger ranOnB = new AtomicInteger();
            assertThat(b.runExclusively(job, MINUTE, Duration.ZERO, ranOnB::incrementAndGet)).isFalse();
            assertThat(ranOnB).hasValue(0);

            finish.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS)).isTrue();
            assertThat(b.runExclusively(job, MINUTE, Duration.ZERO, ranOnB::incrementAndGet)).as("free again").isTrue();
            assertThat(ranOnB).hasValue(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void theSameTurnIsntRunAgainByACopyWhoseTimerFiredAMomentLater() {
        JobLock a = copy("a");
        String job = job();
        AtomicInteger runs = new AtomicInteger();

        assertThat(a.runExclusively(job, MINUTE, MINUTE, runs::incrementAndGet)).isTrue();
        assertThat(copy("b").runExclusively(job, MINUTE, MINUTE, runs::incrementAndGet)).isFalse();
        assertThat(a.runExclusively(job, MINUTE, MINUTE, runs::incrementAndGet)).as("this copy too").isFalse();

        // The turn is over
        jdbc.sql("UPDATE job_locks SET locked_until = now() - interval '1 second' WHERE name = :n").param("n", job).update();
        assertThat(copy("b").runExclusively(job, MINUTE, MINUTE, runs::incrementAndGet)).isTrue();
        assertThat(runs).hasValue(2);
    }

    @Test
    void aCopyThatDiedMidJobHoldsItOnlyUntilAtMost() {
        String job = job();
        // Copy "a" took the lock and then died: its row stays, no release ever comes
        jdbc.sql("INSERT INTO job_locks (name, locked_until, locked_at, locked_by) VALUES (:n, now() + interval '1 hour', now(), 'a')")
            .param("n", job).update();
        AtomicInteger runs = new AtomicInteger();

        assertThat(copy("b").runExclusively(job, MINUTE, Duration.ZERO, runs::incrementAndGet)).isFalse();
        jdbc.sql("UPDATE job_locks SET locked_until = now() - interval '1 millisecond' WHERE name = :n").param("n", job).update();
        assertThat(copy("b").runExclusively(job, MINUTE, Duration.ZERO, runs::incrementAndGet)).isTrue();

        assertThat(runs).hasValue(1);
        assertThat(jdbc.sql("SELECT locked_by FROM job_locks WHERE name = :n").param("n", job).query(String.class).single())
            .isEqualTo("b");
    }

    @Test
    void aFailingJobStillReleasesTheLockAndTheErrorComesThrough() {
        JobLock a = copy("a");
        String job = job();

        assertThatThrownBy(() -> a.runExclusively(job, MINUTE, Duration.ZERO, () -> {
            throw new IllegalStateException("boom");
        })).hasMessage("boom");

        assertThat(copy("b").runExclusively(job, MINUTE, Duration.ZERO, () -> { })).isTrue();
    }

    @Test
    void releasingDoesntFreeALockAnotherCopyTookMeanwhile() {
        JobLock a = copy("a");
        String job = job();

        a.runExclusively(job, MINUTE, Duration.ZERO, () ->
            // a's lock "expired" mid-job and b took it; a's release must leave b's alone
            jdbc.sql("UPDATE job_locks SET locked_by = 'b', locked_until = now() + interval '1 hour' WHERE name = :n")
                .param("n", job).update());

        assertThat(copy("c").runExclusively(job, MINUTE, Duration.ZERO, () -> { })).isFalse();
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
