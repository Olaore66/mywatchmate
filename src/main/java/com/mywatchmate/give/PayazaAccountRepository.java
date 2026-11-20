package com.mywatchmate.give;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface PayazaAccountRepository extends JpaRepository<PayazaAccount, UUID> {

    /** The room's registration, if this link is one of the room's own. */
    @Query("select a from PayazaAccount a join a.links l where a.spaceId = :spaceId and l.id = :linkId")
    Optional<PayazaAccount> findForLink(UUID spaceId, UUID linkId);

    @Query("select a from PayazaAccount a left join fetch a.links where a.spaceId = :spaceId")
    Optional<PayazaAccount> findWithLinks(UUID spaceId);
}
