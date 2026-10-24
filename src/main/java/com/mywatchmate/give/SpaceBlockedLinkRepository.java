package com.mywatchmate.give;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface SpaceBlockedLinkRepository extends JpaRepository<SpaceBlockedLink, SpaceBlockedLinkId> {

    List<SpaceBlockedLink> findBySpaceId(UUID spaceId);

    /** A room's blocked links, newest first, with the link and who blocked it loaded. */
    @Query("""
        select b from SpaceBlockedLink b join fetch b.paymentLink left join fetch b.blockedBy bb left join fetch bb.user
        where b.space.id = :spaceId
        order by b.blockedAt desc
        """)
    List<SpaceBlockedLink> findForSpace(UUID spaceId);
}
