package com.mywatchmate.watch;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WatchSessionRepository extends JpaRepository<WatchSession, UUID> {

    List<WatchSession> findBySpaceIdAndScheduledAtBetweenOrderByScheduledAt(UUID spaceId, Instant from, Instant to);

    List<WatchSession> findByStatus(WatchSession.Status status);
}
