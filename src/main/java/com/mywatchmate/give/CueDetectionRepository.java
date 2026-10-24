package com.mywatchmate.give;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface CueDetectionRepository extends JpaRepository<CueDetection, UUID> {

    List<CueDetection> findBySessionIdOrderByDetectedAtDesc(UUID sessionId);

    /** Was this link already put up in this session recently? */
    boolean existsBySessionIdAndPaymentLinkIdAndStatusAndDetectedAtAfter(UUID sessionId, UUID paymentLinkId,
                                                                        CueDetection.Status status, Instant after);

    /** Prompts in a session that haven't been closed (some may have expired). */
    List<CueDetection> findBySessionIdAndStatusAndClosedAtIsNull(UUID sessionId, CueDetection.Status status);

    /** A prompt in a room, with its session and link loaded. */
    @Query("""
        select d from CueDetection d join fetch d.session s join fetch d.paymentLink
        where d.id = :id and s.space.id = :spaceId
        """)
    Optional<CueDetection> findInSpace(UUID id, UUID spaceId);

    /** Open prompts in a room showing this link (to take down when it's blocked). */
    @Query("""
        select d from CueDetection d
        where d.session.space.id = :spaceId and d.paymentLink.id = :linkId
          and d.status = com.mywatchmate.give.CueDetection.Status.SHOWN and d.closedAt is null
        """)
    List<CueDetection> findOpenInSpaceForLink(UUID spaceId, UUID linkId);

    /** Open prompts anywhere showing this link (to take down when it's blocked everywhere). */
    @Query("""
        select d from CueDetection d join fetch d.session
        where d.paymentLink.id = :linkId
          and d.status = com.mywatchmate.give.CueDetection.Status.SHOWN and d.closedAt is null
        """)
    List<CueDetection> findOpenForLink(UUID linkId);

    @Query("""
        select count(d) from CueDetection d
        where d.session.space.id = :spaceId and d.status = com.mywatchmate.give.CueDetection.Status.SHOWN
          and d.detectedAt >= :from and d.detectedAt < :to
        """)
    long countShownInSpace(UUID spaceId, Instant from, Instant to);
}
