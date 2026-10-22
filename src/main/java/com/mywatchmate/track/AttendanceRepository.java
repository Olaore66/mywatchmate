package com.mywatchmate.track;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AttendanceRepository extends JpaRepository<Attendance, AttendanceId> {

    List<Attendance> findBySessionId(UUID sessionId);

    List<Attendance> findByParticipantUserIdOrderByJoinedAtDesc(UUID userId);
}
