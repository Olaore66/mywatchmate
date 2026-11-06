package com.mywatchmate.auth;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface AccountTokenRepository extends JpaRepository<AccountToken, UUID> {

    // Row lock so two simultaneous requests with the same link can't both use it
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from AccountToken t where t.tokenHash = :tokenHash and t.purpose = :purpose")
    Optional<AccountToken> findForUpdate(String tokenHash, AccountToken.Purpose purpose);

    /** Only the newest link of each kind works: retires the user's other unused ones. */
    @Modifying(flushAutomatically = true)
    @Query("""
        update AccountToken t set t.usedAt = :now
        where t.user.id = :userId and t.purpose = :purpose and t.usedAt is null
        """)
    int retireOpen(UUID userId, AccountToken.Purpose purpose, Instant now);
}
