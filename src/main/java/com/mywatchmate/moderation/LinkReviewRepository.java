package com.mywatchmate.moderation;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LinkReviewRepository extends JpaRepository<LinkReview, UUID> {

    /** A link's decisions, newest first. */
    List<LinkReview> findByPaymentLinkIdOrderByDecidedAtDesc(UUID paymentLinkId);
}
