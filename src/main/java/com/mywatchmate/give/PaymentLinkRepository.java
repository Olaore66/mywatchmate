package com.mywatchmate.give;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface PaymentLinkRepository extends JpaRepository<PaymentLink, UUID> {

    Optional<PaymentLink> findByNormalizedUrl(String normalizedUrl);

    /** The link, locked until the transaction ends: one reviewer decides at a time. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select l from PaymentLink l where l.id = :id")
    Optional<PaymentLink> findForUpdate(UUID id);

    /** Records a "looks like a scam" report; 0 if this person already reported this link. */
    @Modifying
    @Query(value = """
        INSERT INTO payment_link_reports (payment_link_id, reporter_key, detection_id, reason)
        VALUES (:linkId, :reporterKey, :detectionId, :reason)
        ON CONFLICT DO NOTHING
        """, nativeQuery = true)
    int insertReport(UUID linkId, String reporterKey, UUID detectionId, String reason);

    /** One more report. In the database, so two viewers reporting at once both count. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update PaymentLink l set l.reportCount = l.reportCount + 1, l.version = l.version + 1 where l.id = :id")
    int countReport(UUID id);

    /** Holds the link for review if it's allowed and has enough reports; 1 if this call held it. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
        update PaymentLink l
        set l.status = com.mywatchmate.give.PaymentLink.Status.HELD, l.blockedReason = :reason,
            l.statusChangedAt = :now, l.version = l.version + 1
        where l.id = :id and l.status = com.mywatchmate.give.PaymentLink.Status.ALLOWED
          and l.reportCount >= :threshold
        """)
    int holdIfReported(UUID id, int threshold, String reason, Instant now);

    /** Reports a review has dealt with stop counting towards holding the link. */
    @Modifying
    @Query(value = """
        UPDATE payment_link_reports SET review_id = :reviewId
        WHERE payment_link_id = :linkId AND review_id IS NULL
        """, nativeQuery = true)
    int markReportsReviewed(UUID linkId, UUID reviewId);

    /** HELD links, longest waiting first. */
    @Query("""
        select l from PaymentLink l where l.status = com.mywatchmate.give.PaymentLink.Status.HELD
        order by l.statusChangedAt, l.id
        """)
    List<PaymentLink> findHeld(Limit limit);

    /** HELD links after the cursor (statusChangedAt, id), longest waiting first. */
    @Query("""
        select l from PaymentLink l where l.status = com.mywatchmate.give.PaymentLink.Status.HELD
          and (l.statusChangedAt > :at or (l.statusChangedAt = :at and l.id > :id))
        order by l.statusChangedAt, l.id
        """)
    List<PaymentLink> findHeldAfter(Instant at, UUID id, Limit limit);

    /** BLOCKED links, most recently blocked first. */
    @Query("""
        select l from PaymentLink l where l.status = com.mywatchmate.give.PaymentLink.Status.BLOCKED
        order by l.statusChangedAt desc, l.id desc
        """)
    List<PaymentLink> findBlocked(Limit limit);

    /** BLOCKED links after the cursor (statusChangedAt, id), most recently blocked first. */
    @Query("""
        select l from PaymentLink l where l.status = com.mywatchmate.give.PaymentLink.Status.BLOCKED
          and (l.statusChangedAt < :at or (l.statusChangedAt = :at and l.id < :id))
        order by l.statusChangedAt desc, l.id desc
        """)
    List<PaymentLink> findBlockedAfter(Instant at, UUID id, Limit limit);

    long countByStatus(PaymentLink.Status status);
}
