package com.mywatchmate.give;

import java.util.UUID;

/**
 * Whose Payaza account an in-room checkout pays into, and how to describe it to the giver.
 *
 * @param churchId set for a verified church (shown with a "verified" badge); null for a room's own registration
 */
public record CheckoutAccount(String publicKey, PayazaAccount.Mode mode, String merchantName, String currencyCode,
                              UUID churchId) {

    public static CheckoutAccount of(PayazaAccount room) {
        return new CheckoutAccount(room.getPublicKey(), room.getMode(), room.getMerchantName(), room.getCurrencyCode(), null);
    }

    public boolean verifiedChurch() {
        return churchId != null;
    }
}
