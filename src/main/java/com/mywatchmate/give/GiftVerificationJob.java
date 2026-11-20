package com.mywatchmate.give;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Every minute: asks Payaza about in-room payments that haven't been confirmed yet (the giver closed the tab, or
 * Payaza was still processing when they finished). Stops asking after {@code mywatchmate.payaza.verify-for}.
 */
@Component
@RequiredArgsConstructor
public class GiftVerificationJob {

    private final GiftService gifts;

    /** Timer and single-copy lock: {@link com.mywatchmate.config.ScheduledJobs}. */
    public void run() {
        gifts.confirmPendingCheckouts();
    }
}
