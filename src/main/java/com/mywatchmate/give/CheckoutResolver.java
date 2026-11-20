package com.mywatchmate.give;

import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * The one place that decides where a Give prompt's money goes, inside the room:
 * <ol>
 *   <li>A link a church claims: that church's key, only while it's VERIFIED (otherwise a new tab).</li>
 *   <li>A link this room registered as its own: the room's key.</li>
 *   <li>Anything else: none; the link opens in a new tab.</li>
 * </ol>
 * A church's claim beats any room registration, so a room can't route a church's link to another key, and a
 * suspended church's links don't fall back to whatever a room registered.
 */
@Component
@RequiredArgsConstructor
public class CheckoutResolver {

    private final PayazaAccountRepository rooms;
    private final VerifiedChurches churches;

    public Optional<CheckoutAccount> resolve(UUID spaceId, PaymentLink link) {
        if (churches.claims(link.getId())) {
            return churches.verifiedFor(link.getId());
        }
        return rooms.findForLink(spaceId, link.getId()).map(CheckoutAccount::of);
    }
}
