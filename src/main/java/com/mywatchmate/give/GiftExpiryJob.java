package com.mywatchmate.give;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** Every 10 minutes: gifts opened over an hour ago with no result reported become UNKNOWN. */
@Component
@RequiredArgsConstructor
public class GiftExpiryJob {

    private final GiftService gifts;
    private final TransactionTemplate tx;

    /** Timer and single-copy lock: {@link com.mywatchmate.config.ScheduledJobs}. */
    public void run() {
        expire();
    }

    /** @return how many gifts became UNKNOWN */
    public int expire() {
        Integer expired = tx.execute(status -> gifts.expireUnreported());
        return expired == null ? 0 : expired;
    }
}
