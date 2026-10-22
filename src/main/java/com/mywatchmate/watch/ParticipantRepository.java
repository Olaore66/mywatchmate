package com.mywatchmate.watch;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ParticipantRepository extends JpaRepository<Participant, UUID> {

    Optional<Participant> findBySpaceIdAndUserId(UUID spaceId, UUID userId);

    Optional<Participant> findByGuestTokenHash(String guestTokenHash);

    List<Participant> findBySpaceIdAndLeftAtIsNull(UUID spaceId);

    List<Participant> findByUserId(UUID userId);
}
