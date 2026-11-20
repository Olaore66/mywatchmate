package com.mywatchmate.give;

import java.util.Optional;
import java.util.UUID;

/** What giving needs from the church registry (implemented in the church package). */
public interface VerifiedChurches {

    /** The verified church that owns this payment link, ready for an in-room checkout. */
    Optional<CheckoutAccount> verifiedFor(UUID paymentLinkId);

    /** A church has this link (applied, verified or suspended): no room may put its own key behind it. */
    boolean claims(UUID paymentLinkId);
}
