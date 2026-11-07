package com.mywatchmate.config;

import com.mywatchmate.cluster.JobLock;
import com.mywatchmate.connect.ChatPartitionJob;
import com.mywatchmate.give.GiftExpiryJob;
import com.mywatchmate.invite.InviteDispatchJob;
import com.mywatchmate.notify.ReminderDispatchJob;
import com.mywatchmate.sessions.SessionGeneratorJob;
import com.mywatchmate.sessions.SessionLifecycleJob;
import com.mywatchmate.track.AttendanceJob;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Every background job's timer, in one place. Each turn runs on one copy of the app only ({@link JobLock}),
 * so adding copies doesn't run a job twice. The jobs themselves are plain beans that tests call directly.
 * Off with the rest of the scheduling (mywatchmate.jobs.enabled=false).
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "mywatchmate.jobs.enabled", havingValue = "true", matchIfMissing = true)
public class ScheduledJobs {

    private static final Duration FIVE_MINUTES = Duration.ofMinutes(5);
    // For the 30-second jobs: another copy's timer firing a few seconds later skips that turn
    private static final Duration FREQUENT = Duration.ofSeconds(20);

    private final JobLock locks;
    private final SessionGeneratorJob sessionGenerator;
    private final SessionLifecycleJob sessionLifecycle;
    private final ReminderDispatchJob reminders;
    private final InviteDispatchJob invites;
    private final AttendanceJob attendance;
    private final GiftExpiryJob giftExpiry;
    private final ChatPartitionJob chatPartitions;

    @Scheduled(initialDelayString = "PT10S", fixedDelayString = "${mywatchmate.sessions.generate-interval:PT1H}")
    void generateSessions() {
        locks.runExclusively("session-generator", Duration.ofMinutes(30), Duration.ofMinutes(30), sessionGenerator::run);
    }

    @Scheduled(initialDelayString = "PT15S", fixedDelayString = "${mywatchmate.sessions.lifecycle-interval:PT30S}")
    void sessionLifecycle() {
        locks.runExclusively("session-lifecycle", FIVE_MINUTES, FREQUENT, sessionLifecycle::run);
    }

    @Scheduled(initialDelayString = "PT20S", fixedDelayString = "${mywatchmate.reminders.dispatch-interval:PT30S}")
    void sendReminders() {
        locks.runExclusively("reminder-dispatch", FIVE_MINUTES, FREQUENT, reminders::run);
    }

    @Scheduled(initialDelayString = "PT25S", fixedDelayString = "${mywatchmate.invites.dispatch-interval:PT30S}")
    void sendInvites() {
        locks.runExclusively("invite-dispatch", FIVE_MINUTES, FREQUENT, invites::run);
    }

    @Scheduled(initialDelayString = "PT20S", fixedDelayString = "${mywatchmate.track.attendance-interval:PT30S}")
    void creditAttendance() {
        locks.runExclusively("attendance", FIVE_MINUTES, FREQUENT, attendance::run);
    }

    @Scheduled(initialDelayString = "PT1M", fixedDelayString = "${mywatchmate.give.expiry-interval:PT10M}")
    void expireGifts() {
        locks.runExclusively("gift-expiry", FIVE_MINUTES, FIVE_MINUTES, giftExpiry::run);
    }

    @Scheduled(cron = "0 0 3 * * *", zone = "UTC")
    void createChatPartitions() {
        locks.runExclusively("chat-partitions", Duration.ofHours(1), Duration.ofHours(1), chatPartitions::ensurePartitions);
    }
}
