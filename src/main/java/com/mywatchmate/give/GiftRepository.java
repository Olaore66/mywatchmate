package com.mywatchmate.give;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface GiftRepository extends JpaRepository<Gift, UUID> {

    List<Gift> findBySessionIdOrderByOpenedAtDesc(UUID sessionId);

    Optional<Gift> findByProviderTxnRef(String providerTxnRef);

    /** A gift with whoever opened it loaded, for checking it's the caller's. */
    @Query("select g from Gift g join fetch g.participant p left join fetch p.user where g.id = :id")
    Optional<Gift> findWithGiver(UUID id);

    /** Everything the user opened, in any room (as a guest before signing up too), newest first. */
    @Query("""
        select g from Gift g join fetch g.session s join fetch s.space join fetch g.paymentLink
        where g.participant.user.id = :userId
        order by g.openedAt desc
        """)
    List<Gift> findForUser(UUID userId);

    /** A room's gifts opened in [from, to). */
    @Query("""
        select g from Gift g
        where g.session.space.id = :spaceId and g.openedAt >= :from and g.openedAt < :to
        """)
    List<Gift> findInSpace(UUID spaceId, Instant from, Instant to);

    /** In-room payments Payaza hasn't confirmed yet, opened after the cutoff, oldest first. */
    @Query("""
        select g from Gift g join fetch g.session s join fetch s.space join fetch g.participant p left join fetch p.user
        where g.status = com.mywatchmate.give.Gift.Status.OPENED and g.checkoutReference is not null
          and g.openedAt >= :since
        order by g.openedAt
        """)
    List<Gift> findCheckoutsAwaitingPayaza(Instant since, org.springframework.data.domain.Limit limit);

    /** In-room payments Payaza never settled within the checking window: we'll never know. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
        update Gift g set g.status = com.mywatchmate.give.Gift.Status.UNKNOWN
        where g.status = com.mywatchmate.give.Gift.Status.OPENED and g.checkoutReference is not null
          and g.openedAt < :before
        """)
    int expireCheckoutsOpenedBefore(Instant before);

    /** Opened long enough ago with no result: we'll never know. Loaded gifts are refreshed afterwards. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
        update Gift g set g.status = com.mywatchmate.give.Gift.Status.UNKNOWN
        where g.status = com.mywatchmate.give.Gift.Status.OPENED and g.openedAt < :before
          and g.checkoutReference is null
        """)
    int expireOpenedBefore(Instant before);
}
