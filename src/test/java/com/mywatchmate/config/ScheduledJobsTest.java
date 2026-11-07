package com.mywatchmate.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.mywatchmate.cluster.JobLock;
import com.mywatchmate.connect.ChatPartitionJob;
import com.mywatchmate.give.GiftExpiryJob;
import com.mywatchmate.invite.InviteDispatchJob;
import com.mywatchmate.notify.ReminderDispatchJob;
import com.mywatchmate.sessions.SessionGeneratorJob;
import com.mywatchmate.sessions.SessionLifecycleJob;
import com.mywatchmate.track.AttendanceJob;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.annotation.Scheduled;

@ExtendWith(MockitoExtension.class)
class ScheduledJobsTest {

    @Mock JobLock locks;
    @Mock SessionGeneratorJob sessionGenerator;
    @Mock SessionLifecycleJob sessionLifecycle;
    @Mock ReminderDispatchJob reminders;
    @Mock InviteDispatchJob invites;
    @Mock AttendanceJob attendance;
    @Mock GiftExpiryJob giftExpiry;
    @Mock ChatPartitionJob chatPartitions;

    private ScheduledJobs jobs;
    private final List<String> locked = new ArrayList<>();

    @BeforeEach
    void setUp() {
        jobs = new ScheduledJobs(locks, sessionGenerator, sessionLifecycle, reminders, invites, attendance, giftExpiry,
            chatPartitions);
    }

    private void lockIsFree() {
        when(locks.runExclusively(anyString(), any(), any(), any())).thenAnswer(call -> {
            Duration atMost = call.getArgument(1);
            Duration atLeast = call.getArgument(2);
            assertThat(atLeast).as("held at most as long as at least").isLessThanOrEqualTo(atMost);
            locked.add(call.getArgument(0));
            ((Runnable) call.getArgument(3)).run();
            return true;
        });
    }

    @Test
    void eachTimerRunsItsJobUnderItsOwnLock() {
        lockIsFree();

        jobs.generateSessions();
        jobs.sessionLifecycle();
        jobs.sendReminders();
        jobs.sendInvites();
        jobs.creditAttendance();
        jobs.expireGifts();
        jobs.createChatPartitions();

        assertThat(locked).containsExactly("session-generator", "session-lifecycle", "reminder-dispatch", "invite-dispatch",
            "attendance", "gift-expiry", "chat-partitions");
        verify(sessionGenerator).run();
        verify(sessionLifecycle).run();
        verify(reminders).run();
        verify(invites).run();
        verify(attendance).run();
        verify(giftExpiry).run();
        verify(chatPartitions).ensurePartitions();
    }

    @Test
    void whenAnotherCopyHoldsTheLockNothingRunsHere() {
        when(locks.runExclusively(anyString(), any(), any(), any())).thenReturn(false);

        jobs.sendInvites();
        jobs.creditAttendance();

        verifyNoInteractions(invites, attendance);
    }

    @Test
    void everyJobHasATimerHereAndNoneInTheJobItself() {
        List<Method> timers = Arrays.stream(ScheduledJobs.class.getDeclaredMethods())
            .filter(m -> m.isAnnotationPresent(Scheduled.class)).toList();
        assertThat(timers).hasSize(7);

        for (Class<?> job : List.of(SessionGeneratorJob.class, SessionLifecycleJob.class, ReminderDispatchJob.class,
                InviteDispatchJob.class, AttendanceJob.class, GiftExpiryJob.class, ChatPartitionJob.class)) {
            assertThat(Arrays.stream(job.getDeclaredMethods()).filter(m -> m.isAnnotationPresent(Scheduled.class)))
                .as("%s schedules itself (it would skip the lock)", job.getSimpleName()).isEmpty();
        }
    }
}
