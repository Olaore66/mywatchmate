package com.mywatchmate.church;

import com.mywatchmate.give.CheckoutAccount;
import com.mywatchmate.give.VerifiedChurches;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Answers giving's question: is this link a verified church's, and with which key? */
@Component
@RequiredArgsConstructor
public class ChurchDirectory implements VerifiedChurches {

    private final ChurchRepository churches;

    @Override
    @Transactional(readOnly = true)
    public Optional<CheckoutAccount> verifiedFor(UUID paymentLinkId) {
        return churches.findByLink(paymentLinkId)
            .filter(c -> c.getStatus() == Church.Status.VERIFIED)
            .map(c -> new CheckoutAccount(c.getPublicKey(), c.getMode(), c.getName(), c.getCurrencyCode(), c.getId()));
    }

    @Override
    @Transactional(readOnly = true)
    public boolean claims(UUID paymentLinkId) {
        return churches.findByLink(paymentLinkId).isPresent();
    }
}
