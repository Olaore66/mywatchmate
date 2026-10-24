package com.mywatchmate.track;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface AttendanceRepository extends JpaRepository<Attendance, AttendanceId> {

    List<Attendance> findBySessionId(UUID sessionId);

    List<Attendance> findByParticipantUserIdOrderByJoinedAtDesc(UUID userId);

    /** Who was in a session, longest watchers first, with names loaded. */
    @Query("""
        select a from Attendance a join fetch a.participant p left join fetch p.user
        where a.session.id = :sessionId
        order by a.watchedSeconds desc, a.joinedAt
        """)
    List<Attendance> findForSession(UUID sessionId);

    @Query("select a from Attendance a where a.session.id in :sessionIds")
    List<Attendance> findForSessions(Collection<UUID> sessionIds);

    /** When each session the user counted for started (any of their rooms, as a guest before signing up too). */
    @Query("""
        select s.startedAt from Attendance a join a.session s
        where a.participant.user.id = :userId and a.counted = true and s.startedAt is not null
        """)
    List<Instant> findCountedStartsForUser(UUID userId);

    /** The user's sessions that started in [from, to), newest first, with room and video loaded. */
    @Query("""
        select a from Attendance a join fetch a.session s join fetch s.space
        where a.participant.user.id = :userId and s.startedAt >= :from and s.startedAt < :to
        order by s.startedAt desc
        """)
    List<Attendance> findHistory(UUID userId, Instant from, Instant to);

    @Query("""
        select count(a) from Attendance a join a.session s
        where a.participant.user.id = :userId and a.counted = true and s.startedAt >= :from
        """)
    long countCountedSince(UUID userId, Instant from);
}
