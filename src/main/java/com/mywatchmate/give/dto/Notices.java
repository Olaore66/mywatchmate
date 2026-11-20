package com.mywatchmate.give.dto;

import com.mywatchmate.give.PaymentLink;

/** The words every Give card and checkout shows, so viewers know who they're paying. */
public final class Notices {

    private Notices() {
    }

    public static String paying(PaymentLink link) {
        return paying(link.getMerchantName());
    }

    public static String paying(String merchantName) {
        String who = merchantName == null || merchantName.isBlank() ? "the link's owner" : merchantName;
        return "You're paying " + who + " through Payaza. MyWatchMate never holds your money.";
    }
}
