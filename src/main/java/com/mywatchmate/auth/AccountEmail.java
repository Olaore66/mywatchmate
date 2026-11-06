package com.mywatchmate.auth;

import com.mywatchmate.identity.User;
import com.mywatchmate.notify.EmailMessage;
import org.springframework.web.util.HtmlUtils;

/** The account emails: confirm your address, reset your password, your password was changed. */
final class AccountEmail {

    private AccountEmail() {
    }

    static EmailMessage verify(User user, String link) {
        return render(user, "Confirm your email for MyWatchMate", "Confirm your email",
            "Please confirm this is your email address, so we can send you room invitations and reminders.",
            "Confirm my email", link, "This link works for 48 hours. If you didn't create an account, you can ignore this email.");
    }

    static EmailMessage passwordReset(User user, String link) {
        return render(user, "Reset your MyWatchMate password", "Reset your password",
            "We got a request to reset your password. Choose a new one with the button below.",
            "Reset my password", link,
            "This link works for 1 hour and only once. If you didn't ask for this, you can ignore this email; your password hasn't changed.");
    }

    static EmailMessage passwordChanged(User user) {
        return render(user, "Your MyWatchMate password was changed", "Your password was changed",
            "Your password was just changed, and all your devices were signed out.",
            null, null, "If this wasn't you, reset your password straight away and contact support.");
    }

    private static EmailMessage render(User user, String subject, String heading, String body, String button,
                                       String link, String footer) {
        String text = "Hi " + user.getDisplayName() + ",\n\n" + body + "\n\n"
            + (link == null ? "" : button + ": " + link + "\n\n") + footer + "\n";
        String action = link == null ? "" : """
            <p style="margin:24px 0">
              <a href="%1$s" style="display:inline-block;background:#6b4fd8;color:#ffffff;text-decoration:none;
                 padding:12px 28px;border-radius:8px;font-weight:bold">%2$s</a>
            </p>
            <p style="margin:0;font-size:12px;color:#888;word-break:break-all">Or open this link: %1$s</p>
            """.formatted(HtmlUtils.htmlEscape(link), button);
        String html = """
            <!doctype html>
            <html><body style="margin:0;padding:24px;background:#f6f6f8;font-family:Arial,Helvetica,sans-serif">
              <table role="presentation" width="100%%" cellspacing="0" cellpadding="0"><tr><td align="center">
                <table role="presentation" width="480" cellspacing="0" cellpadding="0"
                       style="max-width:480px;background:#ffffff;border-radius:12px;padding:32px">
                  <tr><td>
                    <p style="margin:0 0 8px;font-size:14px;color:#6b4fd8;font-weight:bold">MyWatchMate</p>
                    <h1 style="margin:0 0 16px;font-size:22px;color:#111">%s</h1>
                    <p style="margin:0 0 8px;color:#333">Hi %s,</p>
                    <p style="margin:0;color:#333;line-height:1.5">%s</p>
                    %s
                    <p style="margin:16px 0 0;font-size:12px;color:#888">%s</p>
                  </td></tr>
                </table>
              </td></tr></table>
            </body></html>
            """.formatted(heading, HtmlUtils.htmlEscape(user.getDisplayName()), body, action, footer);
        return new EmailMessage(user.getEmail(), subject, text, html);
    }
}
