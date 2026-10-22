package com.mywatchmate.give;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentLinkRepository extends JpaRepository<PaymentLink, UUID> {

    Optional<PaymentLink> findByNormalizedUrl(String normalizedUrl);
}
