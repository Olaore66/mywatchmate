package com.mywatchmate.church;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChurchReviewRepository extends JpaRepository<ChurchReview, UUID> {

    List<ChurchReview> findByChurchIdOrderByDecidedAtDesc(UUID churchId);
}
