package com.mywatchmate.give;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface PaymentLinkRepository extends JpaRepository<PaymentLink, UUID> {

    Optional<PaymentLink> findByNormalizedUrl(String normalizedUrl);

    /** Records a "looks like a scam" report; 0 if this person already reported this link. */
    @Modifying
    @Query(value = """
        INSERT INTO payment_link_reports (payment_link_id, reporter_key, detection_id, reason)
        VALUES (:linkId, :reporterKey, :detectionId, :reason)
        ON CONFLICT DO NOTHING
        """, nativeQuery = true)
    int insertReport(UUID linkId, String reporterKey, UUID detectionId, String reason);
}
