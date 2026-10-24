package com.mywatchmate.track;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface NoteRepository extends JpaRepository<Note, UUID> {

    List<Note> findBySpaceIdAndVisibilityOrderByCreatedAtDesc(UUID spaceId, Note.Visibility visibility);

    List<Note> findByAuthorIdOrderByCreatedAtDesc(UUID authorId);

    /** The room's shared notes plus the caller's private ones, newest first. */
    @Query("""
        select n from Note n join fetch n.author a left join fetch a.user
        where n.space.id = :spaceId and (n.visibility = :shared or a.id = :me)
        order by n.createdAt desc
        """)
    List<Note> findVisible(UUID spaceId, UUID me, Note.Visibility shared);

    /** Same, for one session. */
    @Query("""
        select n from Note n join fetch n.author a left join fetch a.user
        where n.space.id = :spaceId and n.session.id = :sessionId and (n.visibility = :shared or a.id = :me)
        order by n.createdAt desc
        """)
    List<Note> findVisibleForSession(UUID spaceId, UUID sessionId, UUID me, Note.Visibility shared);

    Optional<Note> findByIdAndSpaceId(UUID id, UUID spaceId);
}
