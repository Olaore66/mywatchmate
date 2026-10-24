package com.mywatchmate.watch;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface WatchSessionRepository extends JpaRepository<WatchSession, UUID> {

    List<WatchSession> findBySpaceIdAndScheduledAtBetweenOrderByScheduledAt(UUID spaceId, Instant from, Instant to);

    List<WatchSession> findByStatus(WatchSession.Status status);

    Optional<WatchSession> findByIdAndSpaceId(UUID id, UUID spaceId);

    boolean existsByScheduleIdAndScheduledAt(UUID scheduleId, Instant scheduledAt);

    boolean existsBySpaceIdAndStatus(UUID spaceId, WatchSession.Status status);

    Optional<WatchSession> findFirstBySpaceIdAndStatus(UUID spaceId, WatchSession.Status status);

    Optional<WatchSession> findFirstByScheduleIdAndStatusOrderByScheduledAt(UUID scheduleId, WatchSession.Status status);

    List<WatchSession> findBySpaceIdAndStatusAndScheduledAtAfter(UUID spaceId, WatchSession.Status status, Instant after);

    /** Live now, plus scheduled ones before {@code to}, soonest first. */
    @Query("""
        select s from WatchSession s left join fetch s.contentItem
        where s.space.id = :spaceId
          and (s.status = :live or (s.status = :scheduled and s.scheduledAt < :to))
        order by s.scheduledAt
        """)
    List<WatchSession> findUpcoming(UUID spaceId, Instant to, WatchSession.Status live, WatchSession.Status scheduled);

    @Query("""
        select s from WatchSession s left join fetch s.contentItem
        where s.space.id = :spaceId and s.scheduledAt >= :from and s.scheduledAt < :to
        order by s.scheduledAt
        """)
    List<WatchSession> findBetween(UUID spaceId, Instant from, Instant to);

    /** Live and upcoming sessions in every active room the user is currently in. */
    @Query("""
        select s from WatchSession s join fetch s.space sp left join fetch s.contentItem
        where sp.archivedAt is null
          and exists (select p from Participant p where p.space = sp and p.user.id = :userId and p.leftAt is null)
          and (s.status = :live or (s.status = :scheduled and s.scheduledAt >= :from and s.scheduledAt < :to))
        order by s.scheduledAt
        """)
    List<WatchSession> findUpcomingForUser(UUID userId, Instant from, Instant to,
                                           WatchSession.Status live, WatchSession.Status scheduled);

    /** Scheduled sessions whose time has come, locked so a second server skips them. */
    @Query(value = """
        select * from watch_sessions
        where status = 'SCHEDULED' and scheduled_at <= :now
        order by scheduled_at
        limit :limit
        for update skip locked
        """, nativeQuery = true)
    List<WatchSession> lockDueToStart(Instant now, int limit);

    /** Live sessions, locked so a second server running the attendance job skips them. */
    @Query(value = "select * from watch_sessions where status = 'LIVE' for update skip locked", nativeQuery = true)
    List<WatchSession> lockLive();

    /**
     * When each session the user was expected at started: sessions that went live in their rooms
     * while they were a member. Missed ones (never started) don't count against anyone.
     */
    @Query("""
        select s.startedAt from WatchSession s, Participant p
        where p.user.id = :userId and s.space = p.space
          and s.status = :ended and s.startedAt is not null
          and p.joinedAt <= s.startedAt and (p.leftAt is null or p.leftAt > s.startedAt)
        """)
    List<Instant> findExpectedStartsForUser(UUID userId, WatchSession.Status ended);

    /** Sessions in a room that went live in [from, to), oldest first. */
    @Query("""
        select s from WatchSession s
        where s.space.id = :spaceId and s.startedAt is not null and s.startedAt >= :from and s.startedAt < :to
        order by s.startedAt
        """)
    List<WatchSession> findStartedBetween(UUID spaceId, Instant from, Instant to);

    /** A schedule changed: drop its future sittings so they can be regenerated (reminders cascade). */
    @Modifying(flushAutomatically = true)
    @Query("""
        delete from WatchSession s
        where s.schedule.id = :scheduleId and s.status = :scheduled and s.scheduledAt > :now
        """)
    int deleteFutureOf(UUID scheduleId, Instant now, WatchSession.Status scheduled);

    /** A schedule was stopped: keep its future sittings for history, but cancelled. */
    @Modifying(flushAutomatically = true)
    @Query("""
        update WatchSession s set s.status = :cancelled
        where s.schedule.id = :scheduleId and s.status = :scheduled and s.scheduledAt > :now
        """)
    int cancelFutureOf(UUID scheduleId, Instant now, WatchSession.Status scheduled, WatchSession.Status cancelled);
}
