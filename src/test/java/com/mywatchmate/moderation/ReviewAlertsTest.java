package com.mywatchmate.moderation;

import static com.mywatchmate.testsupport.Fixtures.user;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mywatchmate.give.LinkHeld;
import com.mywatchmate.give.PaymentLink;
import com.mywatchmate.give.PaymentLinkRepository;
import com.mywatchmate.identity.User;
import com.mywatchmate.identity.UserRepository;
import com.mywatchmate.notify.AfterCommitMailer;
import com.mywatchmate.notify.EmailMessage;
import com.mywatchmate.watch.SpaceProperties;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class ReviewAlertsTest {

    @Mock PaymentLinkRepository links;
    @Mock UserRepository users;
    @Mock AfterCommitMailer mailer;

    private ReviewAlerts alerts() {
        return new ReviewAlerts(links, users, mailer, new SpaceProperties(50, "https://www.mywatchmates.com/"));
    }

    @Test
    void everyMemberOfStaffIsEmailedALinkToTheReviewScreen() {
        PaymentLink link = new PaymentLink("https://pay.payaza.africa/give/x?a=1&b=<2>", PaymentLink.Provider.PAYAZA);
        ReflectionTestUtils.setField(link, "id", UUID.randomUUID());
        link.setReportCount(3);
        when(links.findById(link.getId())).thenReturn(Optional.of(link));
        User rita = user("<i>Rita</i>");
        User max = user("Max");
        when(users.findByStaffRoleIsNotNull()).thenReturn(List.of(rita, max));

        alerts().onHeld(new LinkHeld(link.getId()));

        ArgumentCaptor<EmailMessage> sent = ArgumentCaptor.forClass(EmailMessage.class);
        verify(mailer, times(2)).send(sent.capture());
        EmailMessage first = sent.getAllValues().getFirst();
        String review = "https://www.mywatchmates.com/admin/links/" + link.getId();
        assertThat(first.to()).isEqualTo(rita.getEmail());
        assertThat(first.subject()).isEqualTo("A payment link needs review");
        assertThat(first.text()).contains("3 times").contains(link.getNormalizedUrl()).contains(review);
        assertThat(first.html()).contains(review).contains("&lt;i&gt;Rita&lt;/i&gt;").contains("&amp;b=&lt;2&gt;")
            .doesNotContain("<i>Rita</i>");
        assertThat(sent.getAllValues().get(1).to()).isEqualTo(max.getEmail());
    }

    @Test
    void aLinkThatIsGoneSendsNothing() {
        UUID id = UUID.randomUUID();
        when(links.findById(id)).thenReturn(Optional.empty());

        alerts().onHeld(new LinkHeld(id));

        verify(mailer, never()).send(any());
    }
}
