package com.mywatchmate.auth;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface UserIdentityRepository extends JpaRepository<UserIdentity, UUID> {

    /** The identity with its user loaded. */
    @Query("select i from UserIdentity i join fetch i.user where i.provider = :provider and i.subject = :subject")
    Optional<UserIdentity> findWithUser(UserIdentity.Provider provider, String subject);

    Optional<UserIdentity> findByUserIdAndProvider(UUID userId, UserIdentity.Provider provider);
}
