package com.mywatchmate.church;

import static com.mywatchmate.testsupport.Fixtures.user;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mywatchmate.church.dto.ChurchDecisionRequest.Decision;
import com.mywatchmate.give.PayazaAccount;
import com.mywatchmate.give.PaymentLink;
import com.mywatchmate.identity.User;
import com.mywatchmate.identity.UserRepository;
import com.mywatchmate.notify.AfterCommitMailer;
import com.mywatchmate.notify.EmailMessage;
import com.mywatchmate.watch.SpaceProperties;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class ChurchUnitTest {

    @Mock ChurchRepository churches;

    private Church church(Church.Status status) {
        Church church = new Church("Streams of Joy", user("Grace"), Instant.parse("2026-11-02T00:00:00Z"));
        ReflectionTestUtils.setField(church, "id", UUID.randomUUID());
        church.setPublicKey("PZ78-PKLIVE-STREAMSOFJOY");
        church.setMode(PayazaAccount.Mode.LIVE);
        church.changeStatus(status, Instant.parse("2026-11-03T00:00:00Z"));
        return church;
    }

    @ParameterizedTest
    @CsvSource({
        "APPROVE,   PENDING,   VERIFIED",
        "REJECT,    PENDING,   REJECTED",
        "SUSPEND,   VERIFIED,  SUSPENDED",
        "REINSTATE, SUSPENDED, VERIFIED",
        "APPROVE,   VERIFIED,  ",
        "REJECT,    VERIFIED,  ",
        "SUSPEND,   PENDING,   ",
        "REINSTATE, VERIFIED,  ",
        "APPROVE,   REJECTED,  ",
    })
    void onlyTheseMovesAreAllowed(Decision decision, Church.Status from, Church.Status to) {
        assertThat(ChurchReviewService.target(decision, from)).isEqualTo(to);
    }

    @Nested
    class TheDirectoryGivingAsks {

        private final PaymentLink link = new PaymentLink("https://business.payaza.africa/pay/nsppd", PaymentLink.Provider.PAYAZA);

        @Test
        void onlyAVerifiedChurchGetsPaidAndAnyClaimCounts() {
            ReflectionTestUtils.setField(link, "id", UUID.randomUUID());
            ChurchDirectory directory = new ChurchDirectory(churches);
            Church verified = church(Church.Status.VERIFIED);
            when(churches.findByLink(link.getId())).thenReturn(Optional.of(verified));

            assertThat(directory.verifiedFor(link.getId())).hasValueSatisfying(account -> {
                assertThat(account.publicKey()).isEqualTo("PZ78-PKLIVE-STREAMSOFJOY");
                assertThat(account.merchantName()).isEqualTo("Streams of Joy");
                assertThat(account.churchId()).isEqualTo(verified.getId());
                assertThat(account.mode()).isEqualTo(PayazaAccount.Mode.LIVE);
            });
            assertThat(directory.claims(link.getId())).isTrue();

            when(churches.findByLink(link.getId())).thenReturn(Optional.of(church(Church.Status.PENDING)));
            assertThat(directory.verifiedFor(link.getId())).isEmpty();
            assertThat(directory.claims(link.getId())).isTrue();

            when(churches.findByLink(link.getId())).thenReturn(Optional.empty());
            assertThat(directory.claims(link.getId())).isFalse();
        }
    }

    @Nested
    class StaffAlerts {

        @Mock UserRepository users;
        @Mock AfterCommitMailer mailer;

        @Test
        void everyMemberOfStaffHearsAboutApplicationsAndChanges() {
            Church church = church(Church.Status.PENDING);
            when(churches.findById(church.getId())).thenReturn(Optional.of(church));
            User rita = user("<b>Rita</b>");
            when(users.findByStaffRoleIsNotNull()).thenReturn(List.of(rita));
            ChurchAlerts alerts = new ChurchAlerts(churches, users, mailer, new SpaceProperties(50, "https://www.mywatchmates.com"));

            alerts.onApplied(new ChurchEvents.Applied(church.getId()));
            alerts.onChangeRequested(new ChurchEvents.ChangeRequested(church.getId()));
            alerts.onApplied(new ChurchEvents.Applied(UUID.randomUUID()));

            ArgumentCaptor<EmailMessage> sent = ArgumentCaptor.forClass(EmailMessage.class);
            verify(mailer, times(2)).send(sent.capture());
            EmailMessage first = sent.getAllValues().getFirst();
            assertThat(first.subject()).isEqualTo("A church applied to be verified");
            assertThat(first.text()).contains("Streams of Joy", "/admin/churches/" + church.getId());
            assertThat(first.html()).contains("&lt;b&gt;Rita&lt;/b&gt;").doesNotContain("<b>Rita</b>");
            assertThat(sent.getAllValues().get(1).subject()).contains("change its payment details");
        }
    }
}
