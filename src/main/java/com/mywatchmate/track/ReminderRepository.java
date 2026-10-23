package com.mywatchmate.track;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface ReminderRepository extends JpaRepository<Reminder, UUID> {

    List<Reminder> findTop100ByStatusAndSendAtLessThanEqualOrderBySendAt(Reminder.Status status, Instant now);

    boolean existsByUserIdAndSessionIdAndChannel(UUID userId, UUID sessionId, Reminder.Channel channel);

    List<Reminder> findBySessionId(UUID sessionId);

    /** Reminders due now, locked so a second server skips them. */
    @Query(value = """
        select r.id from reminders r
        where r.status = 'PENDING' and r.send_at <= :now
        order by r.send_at
        limit :limit
        for update skip locked
        """, nativeQuery = true)
    List<UUID> lockDue(Instant now, int limit);

    @Query("""
        select r from Reminder r join fetch r.user join fetch r.session s join fetch s.space
        where r.id in :ids
        """)
    List<Reminder> findWithDetails(Collection<UUID> ids);

    /** The user left the room or changed their settings: drop their unsent reminders there. */
    @Modifying(flushAutomatically = true)
    @Query("""
        delete from Reminder r
        where r.user.id = :userId and r.status = :pending
          and r.session.id in (select s.id from WatchSession s where s.space.id = :spaceId)
        """)
    int deletePendingForUserInSpace(UUID userId, UUID spaceId, Reminder.Status pending);

    @Modifying(flushAutomatically = true)
    @Query("update Reminder r set r.status = :cancelled where r.session.id = :sessionId and r.status = :pending")
    int cancelPendingForSession(UUID sessionId, Reminder.Status pending, Reminder.Status cancelled);

    /** After a schedule is stopped: cancel unsent reminders of its now-cancelled sittings. */
    @Modifying(flushAutomatically = true)
    @Query("""
        update Reminder r set r.status = :cancelled
        where r.status = :pending
          and r.session.id in (select s.id from WatchSession s
                               where s.schedule.id = :scheduleId and s.status = :cancelledSession)
        """)
    int cancelPendingForCancelledSessionsOf(UUID scheduleId, Reminder.Status pending, Reminder.Status cancelled,
                                            com.mywatchmate.watch.WatchSession.Status cancelledSession);
}
