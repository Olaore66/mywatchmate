package com.mywatchmate.moderation;

import static com.mywatchmate.testsupport.Fixtures.MONDAY_MIDNIGHT;
import static com.mywatchmate.testsupport.Fixtures.user;
import static org.assertj.core.api.Assertions.assertThat;

import com.mywatchmate.give.PaymentLink;
import com.mywatchmate.give.PaymentLink.Status;
import com.mywatchmate.identity.User;
import com.mywatchmate.identity.dto.MeResponse;
import com.mywatchmate.moderation.LinkReview.Decision;
import com.mywatchmate.moderation.dto.LinkDetail;
import com.mywatchmate.moderation.dto.LinkSummary;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class ModerationEntitiesTest {

    @Test
    void onlyUsersWithARoleAreStaffAndMeSaysWhichRole() {
        User ada = user("Ada");
        assertThat(ada.isStaff()).isFalse();
        assertThat(MeResponse.from(ada).staffRole()).isNull();

        ada.setStaffRole(User.StaffRole.ADMIN);

        assertThat(ada.isStaff()).isTrue();
        assertThat(MeResponse.from(ada).staffRole()).isEqualTo(User.StaffRole.ADMIN);
    }

    @Test
    void aReviewKeepsTheReviewersNameEvenIfTheyRenameLater() {
        User rita = user("Rita");
        PaymentLink link = new PaymentLink("https://pay.payaza.africa/give/x", PaymentLink.Provider.PAYAZA);

        LinkReview review = new LinkReview(link, rita, Decision.CONFIRM_SCAM, Status.HELD, Status.BLOCKED,
            "Wrong account", 3, MONDAY_MIDNIGHT);
        rita.setDisplayName("Rita O.");

        assertThat(review.getReviewerName()).isEqualTo("Rita");
        assertThat(review.getPaymentLink()).isSameAs(link);
        LinkDetail.Review shown = LinkDetail.Review.from(review);
        assertThat(shown.reviewerName()).isEqualTo("Rita");
        assertThat(shown.fromStatus()).isEqualTo(Status.HELD);
        assertThat(shown.toStatus()).isEqualTo(Status.BLOCKED);
        assertThat(shown.reportsReviewed()).isEqualTo(3);
        assertThat(shown.decidedAt()).isEqualTo(MONDAY_MIDNIGHT);
    }

    @Test
    void aSummaryShowsTheLinkAndItsOpenReports() {
        PaymentLink link = new PaymentLink("https://pay.payaza.africa/give/x", PaymentLink.Provider.PAYAZA);
        ReflectionTestUtils.setField(link, "id", UUID.randomUUID());
        link.setMerchantName("St Mark's");
        link.setReportCount(3);
        link.changeStatus(Status.HELD, "Reported", MONDAY_MIDNIGHT);

        LinkSummary summary = LinkSummary.from(link);

        assertThat(summary.id()).isEqualTo(link.getId());
        assertThat(summary.url()).isEqualTo("https://pay.payaza.africa/give/x");
        assertThat(summary.merchantName()).isEqualTo("St Mark's");
        assertThat(summary.status()).isEqualTo(Status.HELD);
        assertThat(summary.openReports()).isEqualTo(3);
        assertThat(summary.statusChangedAt()).isEqualTo(MONDAY_MIDNIGHT);
    }
}
