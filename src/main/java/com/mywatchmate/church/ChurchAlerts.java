package com.mywatchmate.church;

import com.mywatchmate.identity.User;
import com.mywatchmate.identity.UserRepository;
import com.mywatchmate.notify.AfterCommitMailer;
import com.mywatchmate.notify.EmailMessage;
import com.mywatchmate.watch.SpaceProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

/** Emails staff when a church applies or asks for a change, so it doesn't wait unnoticed. Only if it commits. */
@Component
@RequiredArgsConstructor
class ChurchAlerts {

    private final ChurchRepository churches;
    private final UserRepository users;
    private final AfterCommitMailer mailer;
    private final SpaceProperties spaces;

    @EventListener
    void onApplied(ChurchEvents.Applied applied) {
        tell(applied.churchId(), "A church applied to be verified", "applied to be verified on MyWatchMate");
    }

    @EventListener
    void onChangeRequested(ChurchEvents.ChangeRequested requested) {
        tell(requested.churchId(), "A church asked to change its payment details",
            "asked to change its Payaza key or payment links");
    }

    private void tell(java.util.UUID churchId, String subject, String what) {
        churches.findById(churchId).ifPresent(church -> {
            String review = spaces.frontendUrl() + "/admin/churches/" + church.getId();
            for (User member : users.findByStaffRoleIsNotNull()) {
                mailer.send(email(member, subject, church.getName() + " " + what + ".", review));
            }
        });
    }

    static EmailMessage email(User to, String subject, String line, String review) {
        String text = "Hi " + to.getDisplayName() + ",\n\n" + line + "\nFollow the checklist before approving.\n\nReview it: "
            + review + "\n";
        String html = """
            <!doctype html>
            <html><body style="margin:0;padding:24px;background:#f6f6f8;font-family:Arial,Helvetica,sans-serif">
              <div style="max-width:480px;margin:0 auto;background:#ffffff;border-radius:12px;padding:32px">
                <p style="margin:0 0 8px;font-size:14px;color:#6b4fd8;font-weight:bold">MyWatchMate staff</p>
                <h1 style="margin:0 0 16px;font-size:22px;color:#111">%s</h1>
                <p style="margin:0 0 8px;color:#333">Hi %s,</p>
                <p style="margin:0;color:#333;line-height:1.5">%s Follow the checklist before approving.</p>
                <p style="margin:24px 0">
                  <a href="%s" style="display:inline-block;background:#6b4fd8;color:#ffffff;text-decoration:none;
                     padding:12px 28px;border-radius:8px;font-weight:bold">Review it</a>
                </p>
              </div>
            </body></html>
            """.formatted(HtmlUtils.htmlEscape(subject), HtmlUtils.htmlEscape(to.getDisplayName()), HtmlUtils.htmlEscape(line),
            HtmlUtils.htmlEscape(review));
        return new EmailMessage(to.getEmail(), subject, text, html);
    }
}
