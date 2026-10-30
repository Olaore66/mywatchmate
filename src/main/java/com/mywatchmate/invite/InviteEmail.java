package com.mywatchmate.invite;

import com.mywatchmate.notify.EmailMessage;
import org.springframework.web.util.HtmlUtils;

/**
 * Writes the invitation email: plain text plus a branded HTML version with a Join button. Everything a
 * host typed is escaped, so it shows as text and can't add markup to the email.
 */
public final class InviteEmail {

    private InviteEmail() {
    }

    public static EmailMessage render(String to, String inviterName, String roomTitle, String note, String joinUrl) {
        String inviter = inviterName == null || inviterName.isBlank() ? "Someone" : inviterName;
        String subject = inviter + " invited you to watch together in \"" + roomTitle + "\"";

        String text = """
            Hi,

            %s invited you to join "%s" on MyWatchMate, to watch together, chat and keep each other company.
            %s
            Join: %s

            If you weren't expecting this, you can ignore this email.
            """.formatted(inviter, roomTitle, note == null ? "" : "\n\"" + note + "\"\n", joinUrl);

        String safeInviter = HtmlUtils.htmlEscape(inviter);
        String safeRoom = HtmlUtils.htmlEscape(roomTitle);
        String safeUrl = HtmlUtils.htmlEscape(joinUrl);
        String safeNote = note == null ? "" : """
            <p style="margin:16px 0;padding:12px 16px;background:#f4f1fb;border-radius:8px;color:#333;white-space:pre-line">%s</p>
            """.formatted(HtmlUtils.htmlEscape(note));
        String html = """
            <!doctype html>
            <html><body style="margin:0;padding:24px;background:#f6f6f8;font-family:Arial,Helvetica,sans-serif">
              <table role="presentation" width="100%%" cellspacing="0" cellpadding="0"><tr><td align="center">
                <table role="presentation" width="480" cellspacing="0" cellpadding="0"
                       style="max-width:480px;background:#ffffff;border-radius:12px;padding:32px">
                  <tr><td>
                    <p style="margin:0 0 8px;font-size:14px;color:#6b4fd8;font-weight:bold">MyWatchMate</p>
                    <h1 style="margin:0 0 16px;font-size:22px;color:#111">%s invited you to "%s"</h1>
                    <p style="margin:0;color:#333;line-height:1.5">Watch together, chat and keep each other company.</p>
                    %s
                    <p style="margin:24px 0">
                      <a href="%s" style="display:inline-block;background:#6b4fd8;color:#ffffff;text-decoration:none;
                         padding:12px 28px;border-radius:8px;font-weight:bold">Join the room</a>
                    </p>
                    <p style="margin:0;font-size:12px;color:#888">Or open this link: %s</p>
                    <p style="margin:16px 0 0;font-size:12px;color:#888">If you weren't expecting this, you can ignore this email.</p>
                  </td></tr>
                </table>
              </td></tr></table>
            </body></html>
            """.formatted(safeInviter, safeRoom, safeNote, safeUrl, safeUrl);
        return new EmailMessage(to, subject, text, html);
    }
}
