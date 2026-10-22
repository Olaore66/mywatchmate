package com.mywatchmate.give;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SpaceBlockedLinkRepository extends JpaRepository<SpaceBlockedLink, SpaceBlockedLinkId> {

    List<SpaceBlockedLink> findBySpaceId(UUID spaceId);
}
