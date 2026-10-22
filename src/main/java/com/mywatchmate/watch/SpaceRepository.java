package com.mywatchmate.watch;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SpaceRepository extends JpaRepository<Space, UUID> {

    Optional<Space> findByInviteToken(String inviteToken);

    List<Space> findByOwnerIdAndArchivedAtIsNull(UUID ownerId);
}
