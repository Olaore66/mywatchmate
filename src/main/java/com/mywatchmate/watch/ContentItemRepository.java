package com.mywatchmate.watch;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ContentItemRepository extends JpaRepository<ContentItem, UUID> {

    List<ContentItem> findBySpaceIdOrderByCreatedAtDesc(UUID spaceId);

    /** A room's videos with who added them, newest first. */
    @Query("""
        select c from ContentItem c
            left join fetch c.addedBy a
            left join fetch a.user
        where c.space.id = :spaceId
        order by c.createdAt desc
        """)
    List<ContentItem> findForSpace(UUID spaceId);

    Optional<ContentItem> findByIdAndSpaceId(UUID id, UUID spaceId);
}
