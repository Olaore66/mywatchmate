package com.mywatchmate.watch;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ParticipantRepository extends JpaRepository<Participant, UUID> {

    Optional<Participant> findBySpaceIdAndUserId(UUID spaceId, UUID userId);

    Optional<Participant> findByGuestTokenHash(String guestTokenHash);

    List<Participant> findBySpaceIdAndLeftAtIsNull(UUID spaceId);

    List<Participant> findByUserId(UUID userId);

    long countBySpaceIdAndLeftAtIsNull(UUID spaceId);

    /** Everyone who has ever been in the room, including people who left, with accounts loaded. */
    @Query("""
        select p from Participant p left join fetch p.user
        where p.space.id = :spaceId
        order by p.joinedAt
        """)
    List<Participant> findAllWithUsers(UUID spaceId);

    /** Everyone currently in the room, oldest first, with their accounts loaded. */
    @Query("""
        select p from Participant p left join fetch p.user
        where p.space.id = :spaceId and p.leftAt is null
        order by p.joinedAt
        """)
    List<Participant> findActiveWithUsers(UUID spaceId);
}
