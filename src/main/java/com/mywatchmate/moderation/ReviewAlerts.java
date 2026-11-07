package com.mywatchmate.moderation;

import com.mywatchmate.give.LinkHeld;
import com.mywatchmate.give.PaymentLinkRepository;
import com.mywatchmate.identity.User;
import com.mywatchmate.identity.UserRepository;
import com.mywatchmate.notify.AfterCommitMailer;
import com.mywatchmate.notify.EmailMessage;
import com.mywatchmate.watch.SpaceProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

/** Emails every member of staff when viewers' reports hold a link, so the queue doesn't sit unnoticed. */
@Component
@RequiredArgsConstructor
class ReviewAlerts {

    private final PaymentLinkRepository links;
    private final UserRepository users;
    private final AfterCommitMailer mailer;
    private final SpaceProperties spaces;

    /** Runs inside the reporting transaction; the emails only go if it commits. */
    @EventListener
    void onHeld(LinkHeld held) {
        links.findById(held.paymentLinkId()).ifPresent(link -> {
            String review = spaces.frontendUrl() + "/admin/links/" + link.getId();
            for (User member : users.findByStaffRoleIsNotNull()) {
                mailer.send(email(member, link.getNormalizedUrl(), link.getReportCount(), review));
            }
        });
    }

    static EmailMessage email(User to, String url, int reports, String review) {
        String subject = "A payment link needs review";
        String text = """
            Hi %s,

            Viewers reported this payment link as a scam %d times, so it's held (not shown anywhere) until someone reviews it:
            %s

            Review it: %s
            """.formatted(to.getDisplayName(), reports, url, review);
        String html = """
            <!doctype html>
            <html><body style="margin:0;padding:24px;background:#f6f6f8;font-family:Arial,Helvetica,sans-serif">
              <div style="max-width:480px;margin:0 auto;background:#ffffff;border-radius:12px;padding:32px">
                <p style="margin:0 0 8px;font-size:14px;color:#6b4fd8;font-weight:bold">MyWatchMate staff</p>
                <h1 style="margin:0 0 16px;font-size:22px;color:#111">A payment link needs review</h1>
                <p style="margin:0 0 8px;color:#333">Hi %s,</p>
                <p style="margin:0;color:#333;line-height:1.5">Viewers reported this payment link as a scam %d times,
                  so it's held (not shown anywhere) until someone reviews it:</p>
                <p style="margin:12px 0;font-family:monospace;word-break:break-all;color:#111">%s</p>
                <p style="margin:24px 0">
                  <a href="%s" style="display:inline-block;background:#6b4fd8;color:#ffffff;text-decoration:none;
                     padding:12px 28px;border-radius:8px;font-weight:bold">Review it</a>
                </p>
              </div>
            </body></html>
            """.formatted(HtmlUtils.htmlEscape(to.getDisplayName()), reports, HtmlUtils.htmlEscape(url),
            HtmlUtils.htmlEscape(review));
        return new EmailMessage(to.getEmail(), subject, text, html);
    }
}
