package com.mywatchmate.give.dto;

import static com.mywatchmate.testsupport.Fixtures.MONDAY_MIDNIGHT;
import static com.mywatchmate.testsupport.Fixtures.guest;
import static com.mywatchmate.testsupport.Fixtures.member;
import static com.mywatchmate.testsupport.Fixtures.session;
import static com.mywatchmate.testsupport.Fixtures.space;
import static com.mywatchmate.testsupport.Fixtures.user;
import static org.assertj.core.api.Assertions.assertThat;

import com.mywatchmate.give.CueDetection;
import com.mywatchmate.give.Gift;
import com.mywatchmate.give.PaymentLink;
import com.mywatchmate.give.SpaceBlockedLink;
import com.mywatchmate.watch.Participant;
import com.mywatchmate.watch.Space;
import com.mywatchmate.watch.WatchSession;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** The shapes the frontend receives for giving. */
class GiveDtoMappingTest {

    private final Space space = space(user("Ada"));
    private final WatchSession live = session(space, MONDAY_MIDNIGHT);
    private final PaymentLink link = new PaymentLink("https://pay.payaza.africa/give/stmarks", PaymentLink.Provider.PAYAZA);

    @Test
    void theNoticeNamesWhoIsPaidWhenKnown() {
        assertThat(Notices.paying(link))
            .isEqualTo("You're paying the link's owner through Payaza. MyWatchMate never holds your money.");
        link.setMerchantName("  ");
        assertThat(Notices.paying(link)).contains("the link's owner");
        link.setMerchantName("St Mark's Parish");
        assertThat(Notices.paying(link))
            .isEqualTo("You're paying St Mark's Parish through Payaza. MyWatchMate never holds your money.");
    }

    @Test
    void aPromptCarriesTheLinkAndWhenItGoesAway() {
        CueDetection d = new CueDetection(live, CueDetection.CueType.QR, "raw", CueDetection.Status.SHOWN);
        ReflectionTestUtils.setField(d, "id", UUID.randomUUID());
        d.setPaymentLink(link);
        d.setSource(CueDetection.Source.AI);
        d.setDetectedAt(MONDAY_MIDNIGHT);
        d.setExpiresAt(MONDAY_MIDNIGHT.plusSeconds(300));

        PromptResponse p = PromptResponse.from(d);

        assertThat(p.id()).isEqualTo(d.getId());
        assertThat(p.sessionId()).isEqualTo(live.getId());
        assertThat(p.url()).isEqualTo("https://pay.payaza.africa/give/stmarks");
        assertThat(p.provider()).isEqualTo(PaymentLink.Provider.PAYAZA);
        assertThat(p.source()).isEqualTo(CueDetection.Source.AI);
        assertThat(p.shownAt()).isEqualTo(MONDAY_MIDNIGHT);
        assertThat(p.expiresAt()).isEqualTo(MONDAY_MIDNIGHT.plusSeconds(300));
        assertThat(p.notice()).startsWith("You're paying");
    }

    @Test
    void myGiftIsAlwaysMarkedSelfReported() {
        Gift g = new Gift(null, live, guest(space, "Bisi"), link, true);
        g.report(Gift.Status.COMPLETED, 500_000L, "NGN", "PZ-1", MONDAY_MIDNIGHT);

        GiftResponse r = GiftResponse.from(g);

        assertThat(r.spaceId()).isEqualTo(space.getId());
        assertThat(r.spaceTitle()).isEqualTo("6am Prayer Room");
        assertThat(r.sessionId()).isEqualTo(live.getId());
        assertThat(r.url()).isEqualTo(link.getNormalizedUrl());
        assertThat(r.amountMinor()).isEqualTo(500_000L);
        assertThat(r.currency()).isEqualTo("NGN");
        assertThat(r.providerTxnRef()).isEqualTo("PZ-1");
        assertThat(r.anonymous()).isTrue();
        assertThat(r.completedAt()).isEqualTo(MONDAY_MIDNIGHT);
        assertThat(r.selfReported()).isTrue();
    }

    @Test
    void aBlockedLinkShowsWhoBlockedIt() {
        ReflectionTestUtils.setField(link, "id", UUID.randomUUID());
        Participant ada = member(space, space.getOwner(), Participant.Role.OWNER);

        BlockedLinkResponse byAda = BlockedLinkResponse.from(new SpaceBlockedLink(space, link, ada, "scam"));
        BlockedLinkResponse byNobody = BlockedLinkResponse.from(new SpaceBlockedLink(space, link, null, null));

        assertThat(byAda.paymentLinkId()).isEqualTo(link.getId());
        assertThat(byAda.blockedBy().displayName()).isEqualTo("Ada");
        assertThat(byAda.reason()).isEqualTo("scam");
        assertThat(byNobody.blockedBy()).isNull();
    }
}
