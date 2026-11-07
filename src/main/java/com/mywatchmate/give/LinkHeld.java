package com.mywatchmate.give;

import java.util.UUID;

/** Viewers' reports just held a payment link for review. Published inside the reporting transaction. */
public record LinkHeld(UUID paymentLinkId) {
}
