package com.mywatchmate.track;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StreakRepository extends JpaRepository<Streak, UUID> {
}
