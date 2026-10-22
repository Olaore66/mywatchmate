package com.mywatchmate.watch;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ContentItemRepository extends JpaRepository<ContentItem, UUID> {

    List<ContentItem> findBySpaceIdOrderByCreatedAtDesc(UUID spaceId);
}
