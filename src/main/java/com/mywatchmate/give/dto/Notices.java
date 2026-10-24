package com.mywatchmate.give.dto;

import com.mywatchmate.give.PaymentLink;

/** The words every Give card and checkout shows, so viewers know who they're paying. */
public final class Notices {

    private Notices() {
    }

    public static String paying(PaymentLink link) {
        String who = link.getMerchantName() == null || link.getMerchantName().isBlank()
            ? "the link's owner" : link.getMerchantName();
        return "You're paying " + who + " through Payaza. MyWatchMate never holds your money.";
    }
}
