package com.mywatchmate.live;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface PlaybackStateRepository extends JpaRepository<PlaybackState, UUID> {

    /** Creates the room's row if it has none yet; safe when two presses race to create it. */
    @Modifying
    @Query(value = """
        INSERT INTO playback_states (space_id, content_id, status, position_ms, position_at, version)
        SELECT s.id, s.current_content_id, CASE WHEN s.current_content_id IS NULL THEN 'STOPPED' ELSE 'PAUSED' END,
               0, now(), 0
        FROM spaces s WHERE s.id = :spaceId
        ON CONFLICT (space_id) DO NOTHING
        """, nativeQuery = true)
    void insertIfMissing(UUID spaceId);

    /** The row, locked until commit: simultaneous presses in one room take turns, so versions never clash. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from PlaybackState p left join fetch p.content where p.spaceId = :spaceId")
    Optional<PlaybackState> findForUpdate(UUID spaceId);

    @Query("select p from PlaybackState p left join fetch p.content where p.spaceId = :spaceId")
    Optional<PlaybackState> findWithContent(UUID spaceId);
}
