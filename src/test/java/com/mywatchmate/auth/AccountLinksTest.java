package com.mywatchmate.auth;

import static com.mywatchmate.testsupport.Fixtures.MONDAY_MIDNIGHT;
import static com.mywatchmate.testsupport.Fixtures.clockAt;
import static com.mywatchmate.testsupport.Fixtures.user;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mywatchmate.identity.User;
import com.mywatchmate.notify.AfterCommitMailer;
import com.mywatchmate.notify.EmailMessage;
import com.mywatchmate.watch.SpaceProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AccountLinksTest {

    @Mock AccountTokenRepository accountTokens;
    @Mock TokenService tokens;
    @Mock AfterCommitMailer mailer;

    private AccountLinks links;
    private User ada;

    @BeforeEach
    void setUp() {
        links = new AccountLinks(accountTokens, tokens, mailer, new SpaceProperties(50, "https://www.mywatchmates.com"),
            clockAt(MONDAY_MIDNIGHT));
        ada = user("<b>Ada</b>");
    }

    private AccountToken savedToken() {
        ArgumentCaptor<AccountToken> saved = ArgumentCaptor.forClass(AccountToken.class);
        verify(accountTokens).save(saved.capture());
        return saved.getValue();
    }

    private EmailMessage sentEmail() {
        ArgumentCaptor<EmailMessage> sent = ArgumentCaptor.forClass(EmailMessage.class);
        verify(mailer).send(sent.capture());
        return sent.getValue();
    }

    @Test
    void aVerificationLinkLastsTwoDaysAndOnlyItsHashIsStored() {
        when(tokens.newOpaqueToken()).thenReturn("raw-token");
        when(tokens.hash("raw-token")).thenReturn("hashed");

        links.sendVerification(ada);

        AccountToken token = savedToken();
        assertThat(token.getPurpose()).isEqualTo(AccountToken.Purpose.EMAIL_VERIFY);
        assertThat(token.getTokenHash()).isEqualTo("hashed");
        assertThat(token.getExpiresAt()).isEqualTo(MONDAY_MIDNIGHT.plus(AccountLinks.VERIFY_TTL));
        EmailMessage email = sentEmail();
        assertThat(email.to()).isEqualTo(ada.getEmail());
        assertThat(email.text()).contains("https://www.mywatchmates.com/verify-email?token=raw-token");
        assertThat(email.html()).contains("https://www.mywatchmates.com/verify-email?token=raw-token").contains("Confirm my email");
    }

    @Test
    void aResetLinkLastsAnHourAndRetiresEarlierOnes() {
        when(tokens.newOpaqueToken()).thenReturn("raw-token");
        when(tokens.hash("raw-token")).thenReturn("hashed");

        links.sendPasswordReset(ada);

        InOrder order = inOrder(accountTokens);
        order.verify(accountTokens).retireOpen(ada.getId(), AccountToken.Purpose.PASSWORD_RESET, MONDAY_MIDNIGHT);
        order.verify(accountTokens).save(any(AccountToken.class));
        assertThat(savedToken().getExpiresAt()).isEqualTo(MONDAY_MIDNIGHT.plus(AccountLinks.RESET_TTL));
        assertThat(sentEmail().text()).contains("https://www.mywatchmates.com/reset-password?token=raw-token");
    }

    @Test
    void theChangedNoticeHasNoLinkAndNamesAreEscaped() {
        links.sendPasswordChanged(ada);

        EmailMessage email = sentEmail();
        assertThat(email.subject()).isEqualTo("Your MyWatchMate password was changed");
        assertThat(email.html()).doesNotContain("href=").doesNotContain("<b>Ada</b>").contains("&lt;b&gt;Ada&lt;/b&gt;");
    }

    @Test
    void tokensAreUsableUntilSpentOrExpired() {
        AccountToken token = new AccountToken(ada, AccountToken.Purpose.PASSWORD_RESET, "h", MONDAY_MIDNIGHT, MONDAY_MIDNIGHT.plusSeconds(60));

        assertThat(token.isUsable(MONDAY_MIDNIGHT.plusSeconds(59))).isTrue();
        assertThat(token.isUsable(MONDAY_MIDNIGHT.plusSeconds(60))).isFalse();
        token.use(MONDAY_MIDNIGHT);
        assertThat(token.isUsable(MONDAY_MIDNIGHT.plusSeconds(1))).isFalse();
    }
}
