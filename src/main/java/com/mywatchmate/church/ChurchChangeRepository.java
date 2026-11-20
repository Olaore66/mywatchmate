package com.mywatchmate.church;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface ChurchChangeRepository extends JpaRepository<ChurchChange, UUID> {

    Optional<ChurchChange> findByChurchIdAndStatus(UUID churchId, ChurchChange.Status status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from ChurchChange c join fetch c.church where c.id = :id")
    Optional<ChurchChange> findForUpdate(UUID id);

    long countByStatus(ChurchChange.Status status);
}
