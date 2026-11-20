package com.mywatchmate.church;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface ChurchRepository extends JpaRepository<Church, UUID> {

    /** The church that has this payment link, whatever its status. */
    @Query("select c from Church c join c.links l where l.id = :linkId")
    Optional<Church> findByLink(UUID linkId);

    @Query("select c from Church c left join fetch c.links left join fetch c.appliedBy where c.id = :id")
    Optional<Church> findWithLinks(UUID id);

    /** Locked until the transaction ends: one staff decision at a time. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Church c where c.id = :id")
    Optional<Church> findForUpdate(UUID id);

    /** A status's churches, longest in that status first. */
    @Query("select c from Church c where c.status = :status order by c.statusChangedAt, c.id")
    List<Church> findByStatusOldestFirst(Church.Status status, Limit limit);

    @Query("select c from Church c where c.appliedBy.id = :userId order by c.createdAt desc")
    List<Church> findAppliedBy(UUID userId);

    long countByStatus(Church.Status status);

    long countByAppliedByIdAndStatus(UUID userId, Church.Status status);
}
