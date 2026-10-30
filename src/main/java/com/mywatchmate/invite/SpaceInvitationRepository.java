package com.mywatchmate.invite;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface SpaceInvitationRepository extends JpaRepository<SpaceInvitation, UUID> {

    /** A room's invitations, newest first, with who invited them. */
    @Query("""
        select i from SpaceInvitation i left join fetch i.invitedBy b left join fetch b.user
        where i.space.id = :spaceId
        order by i.createdAt desc
        """)
    List<SpaceInvitation> findForSpace(UUID spaceId);

    Optional<SpaceInvitation> findByIdAndSpaceId(UUID id, UUID spaceId);

    /** The waiting (PENDING or SENT) invitation for these addresses in a room. */
    @Query("""
        select i from SpaceInvitation i
        where i.space.id = :spaceId and i.email in :emails
          and i.status in (com.mywatchmate.invite.SpaceInvitation.Status.PENDING,
                           com.mywatchmate.invite.SpaceInvitation.Status.SENT)
        """)
    List<SpaceInvitation> findWaiting(UUID spaceId, Collection<String> emails);

    /** Invitations queued (or re-queued) in this room since then: the daily limit. */
    long countBySpaceIdAndLastQueuedAtAfter(UUID spaceId, Instant since);

    /** Due to be sent, locked so a second server skips them. */
    @Query(value = """
        select cast(id as varchar) from space_invitations
        where status = 'PENDING' and next_attempt_at <= :now
        order by next_attempt_at
        limit :limit
        for update skip locked
        """, nativeQuery = true)
    List<String> lockDue(Instant now, int limit);

    @Query("""
        select i from SpaceInvitation i join fetch i.space s
            left join fetch i.invitedBy b left join fetch b.user
        where i.id in :ids
        """)
    List<SpaceInvitation> findWithDetails(Collection<UUID> ids);
}
