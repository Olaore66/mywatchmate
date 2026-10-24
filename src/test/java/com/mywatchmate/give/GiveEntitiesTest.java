package com.mywatchmate.give;

import static com.mywatchmate.testsupport.Fixtures.MONDAY_MIDNIGHT;
import static com.mywatchmate.testsupport.Fixtures.guest;
import static com.mywatchmate.testsupport.Fixtures.session;
import static com.mywatchmate.testsupport.Fixtures.space;
import static com.mywatchmate.testsupport.Fixtures.user;
import static org.assertj.core.api.Assertions.assertThat;

import com.mywatchmate.watch.Participant;
import com.mywatchmate.watch.Space;
import com.mywatchmate.watch.WatchSession;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class GiveEntitiesTest {

    private final Space space = space(user("Ada"));
    private final WatchSession live = session(space, MONDAY_MIDNIGHT);
    private final PaymentLink link = new PaymentLink("https://pay.payaza.africa/give/x", PaymentLink.Provider.PAYAZA);

    private static Instant at(long seconds) {
        return MONDAY_MIDNIGHT.plusSeconds(seconds);
    }

    @Test
    void aPromptShowsUntilItExpiresOrIsClosed() {
        CueDetection prompt = new CueDetection(live, CueDetection.CueType.QR, "raw", CueDetection.Status.SHOWN);
        prompt.setExpiresAt(at(300));

        assertThat(prompt.isShowing(at(299))).isTrue();
        assertThat(prompt.isShowing(at(300))).isFalse();

        prompt.close(at(10));
        prompt.close(at(20));
        assertThat(prompt.isShowing(at(11))).isFalse();
        assertThat(prompt.getClosedAt()).as("the first close is kept").isEqualTo(at(10));
    }

    @Test
    void onlyShownPromptsWithAnExpiryShow() {
        CueDetection rejected = new CueDetection(live, CueDetection.CueType.QR, "raw", CueDetection.Status.REJECTED);
        rejected.setExpiresAt(at(300));
        CueDetection noExpiry = new CueDetection(live, CueDetection.CueType.QR, "raw", CueDetection.Status.SHOWN);

        assertThat(rejected.isShowing(at(1))).isFalse();
        assertThat(noExpiry.isShowing(at(1))).isFalse();
    }

    @Test
    void aGiftTakesOneResultAndOnlyCompletedOnesHaveACompletionTime() {
        Participant bisi = guest(space, "Bisi");
        Gift gift = new Gift(null, live, bisi, link, true);
        assertThat(gift.awaitsResult()).isTrue();

        gift.report(Gift.Status.COMPLETED, 500_000L, "NGN", "PZ-1", at(60));
        assertThat(gift.awaitsResult()).isFalse();
        assertThat(gift.getCompletedAt()).isEqualTo(at(60));
        assertThat(gift.getReportedAt()).isEqualTo(at(60));
        assertThat(gift.getAmountMinor()).isEqualTo(500_000L);

        Gift cancelled = new Gift(null, live, bisi, link, false);
        cancelled.report(Gift.Status.CANCELLED, null, null, null, at(5));
        assertThat(cancelled.getCompletedAt()).isNull();
    }

    @Test
    void anUnknownGiftStillTakesALateResultOnce() {
        Gift gift = new Gift(null, live, guest(space, "Bisi"), link, false);
        gift.setStatus(Gift.Status.UNKNOWN);
        assertThat(gift.awaitsResult()).isTrue();

        gift.report(Gift.Status.FAILED, null, null, null, at(4000));
        assertThat(gift.awaitsResult()).isFalse();
    }

    @Test
    void blockingALinkRecordsWhy() {
        assertThat(link.isBlocked()).isFalse();

        link.block("Reported by viewers; under review");

        assertThat(link.isBlocked()).isTrue();
        assertThat(link.getStatus()).isEqualTo(PaymentLink.Status.BLOCKED);
        assertThat(link.getBlockedReason()).isEqualTo("Reported by viewers; under review");
    }
}
