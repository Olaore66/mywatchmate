package com.mywatchmate.invite;

import static org.assertj.core.api.Assertions.assertThat;

import com.mywatchmate.notify.EmailMessage;
import org.junit.jupiter.api.Test;

class InviteEmailTest {

    private static final String JOIN = "https://mywatchmates.app/join/abc123";

    @Test
    void namesWhoInvitedThemAndWhereWithAJoinButton() {
        EmailMessage email = InviteEmail.render("kemi@example.com", "Ada", "6am Prayer Room", "See you Sunday!", JOIN);

        assertThat(email.to()).isEqualTo("kemi@example.com");
        assertThat(email.subject()).isEqualTo("Ada invited you to watch together in \"6am Prayer Room\"");
        assertThat(email.text()).contains("Ada invited you to join \"6am Prayer Room\"")
            .contains("\"See you Sunday!\"").contains("Join: " + JOIN);
        assertThat(email.html()).contains("Ada invited you to \"6am Prayer Room\"")
            .contains("href=\"" + JOIN + "\"").contains("Join the room").contains("See you Sunday!");
    }

    @Test
    void withoutANoteOrAKnownInviter() {
        EmailMessage email = InviteEmail.render("kemi@example.com", null, "Room", null, JOIN);

        assertThat(email.subject()).startsWith("Someone invited you");
        assertThat(email.text()).doesNotContain("\"null\"");
        assertThat(email.html()).doesNotContain("background:#f4f1fb");
    }

    @Test
    void whatTheHostTypedCantAddMarkupToTheEmail() {
        EmailMessage email = InviteEmail.render("kemi@example.com", "<b>Ada</b>", "Room <script>x()</script>",
            "<a href=\"https://evil.example\">Click</a>", JOIN);

        assertThat(email.html()).doesNotContain("<script>").doesNotContain("<b>Ada</b>")
            .doesNotContain("<a href=\"https://evil.example\"")
            .contains("&lt;b&gt;Ada&lt;/b&gt;").contains("&lt;script&gt;");
    }
}
