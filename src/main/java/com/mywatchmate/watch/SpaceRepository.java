package com.mywatchmate.watch;

import com.mywatchmate.watch.dto.SpaceSummaryResponse;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface SpaceRepository extends JpaRepository<Space, UUID> {

    Optional<Space> findByInviteToken(String inviteToken);

    List<Space> findByOwnerIdAndArchivedAtIsNull(UUID ownerId);

    /**
     * The rooms a user is currently in, with their role, member count and what's playing,
     * in one query. With {@code archived = true}: archived rooms they own (to restore).
     */
    @Query("""
        select new com.mywatchmate.watch.dto.SpaceSummaryResponse(
            s.id, s.title, s.audienceType, p.role,
            (select count(m) from Participant m where m.space = s and m.leftAt is null),
            c.title, c.thumbnailUrl, s.archivedAt, s.createdAt)
        from Participant p
            join p.space s
            left join s.currentContent c
        where p.user.id = :userId
          and p.leftAt is null
          and ((:archived = false and s.archivedAt is null)
               or (:archived = true and s.archivedAt is not null
                   and p.role = com.mywatchmate.watch.Participant.Role.OWNER))
        order by s.createdAt desc
        """)
    List<SpaceSummaryResponse> findRoomsOf(UUID userId, boolean archived);
}
