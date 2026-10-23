package com.mywatchmate.watch;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ScheduleRepository extends JpaRepository<Schedule, UUID> {

    Optional<Schedule> findByIdAndSpaceIdAndActiveTrue(UUID id, UUID spaceId);

    List<Schedule> findBySpaceIdAndActiveTrue(UUID spaceId);

    List<Schedule> findByActiveTrue();
}
