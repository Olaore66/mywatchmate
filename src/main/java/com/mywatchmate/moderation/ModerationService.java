package com.mywatchmate.moderation;

import com.mywatchmate.auth.AuthPrincipal;
import com.mywatchmate.common.ApiException;
import com.mywatchmate.give.GiveService;
import com.mywatchmate.give.PaymentLink;
import com.mywatchmate.give.PaymentLink.Status;
import com.mywatchmate.give.PaymentLinkRecognizer;
import com.mywatchmate.give.PaymentLinkRepository;
import com.mywatchmate.identity.User;
import com.mywatchmate.moderation.LinkReview.Decision;
import com.mywatchmate.moderation.dto.LinkDetail;
import com.mywatchmate.moderation.dto.LinkQueuePage;
import com.mywatchmate.moderation.dto.LinkSummary;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Staff review of reported payment links. Viewers' reports HOLD a link (blocked meanwhile: unsure means
 * blocked); a reviewer then confirms it as a scam (BLOCKED) or clears it (ALLOWED). The allowed moves:
 * <pre>
 *   HELD    --CONFIRM_SCAM--> BLOCKED     HELD    --CLEAR--> ALLOWED
 *   ALLOWED --CONFIRM_SCAM--> BLOCKED     BLOCKED --CLEAR--> ALLOWED  (undoing a mistake)
 * </pre>
 * The link is locked while deciding, and the decision only applies to the status the reviewer saw, so two
 * reviewers can't overrule each other by accident. Every decision is kept for good in link_reviews, and the
 * reports it dealt with stop counting: a cleared link is only held again by new reports.
 */
@Service
@RequiredArgsConstructor
public class ModerationService {

    static final int DEFAULT_PAGE = 25;
    static final int MAX_PAGE = 100;
    static final String CONFIRMED_REASON = "Confirmed as a scam by MyWatchMate";

    private final StaffAccess staff;
    private final PaymentLinkRepository links;
    private final LinkReviewRepository reviews;
    private final LinkEvidence evidence;
    private final PaymentLinkRecognizer recognizer;
    private final GiveService give;
    private final Clock clock;

    @Transactional(readOnly = true)
    public LinkQueuePage queue(AuthPrincipal principal, Status status, String cursor, Integer limit) {
        staff.require(principal);
        Status listed = status == null ? Status.HELD : status;
        int size = limit == null ? DEFAULT_PAGE : Math.clamp(limit, 1, MAX_PAGE);
        ReviewCursor after = cursor == null || cursor.isBlank() ? null : ReviewCursor.decode(cursor);
        Limit fetch = Limit.of(size + 1);
        List<PaymentLink> found = switch (listed) {
            case HELD -> after == null ? links.findHeld(fetch) : links.findHeldAfter(after.at(), after.id(), fetch);
            case BLOCKED -> after == null ? links.findBlocked(fetch) : links.findBlockedAfter(after.at(), after.id(), fetch);
            case ALLOWED -> throw ApiException.badRequest("INVALID_STATUS", "List HELD (the queue) or BLOCKED links.");
        };
        List<PaymentLink> page = found.size() > size ? found.subList(0, size) : found;
        String next = found.size() > size ? ReviewCursor.of(page.getLast()).encode() : null;
        return new LinkQueuePage(page.stream().map(LinkSummary::from).toList(), next, links.countByStatus(Status.HELD));
    }

    @Transactional(readOnly = true)
    public LinkDetail detail(AuthPrincipal principal, UUID linkId) {
        staff.require(principal);
        return detailOf(links.findById(linkId).orElseThrow(ModerationService::linkNotFound));
    }

    @Transactional
    public LinkDetail decide(AuthPrincipal principal, UUID linkId, Decision decision, String note, Status expectedStatus) {
        User reviewer = staff.require(principal);
        PaymentLink link = links.findForUpdate(linkId).orElseThrow(ModerationService::linkNotFound);
        if (link.getStatus() != expectedStatus) {
            throw ApiException.conflict("LINK_STATUS_CHANGED",
                "This link is now " + link.getStatus() + ": someone decided while you were looking. Reload it.");
        }
        apply(link, reviewer, decision, note.strip());
        return detailOf(link);
    }

    /** Blocks a payment link everywhere straight away, whether or not anyone has seen or reported it. */
    @Transactional
    public LinkDetail block(AuthPrincipal principal, String url, String note) {
        User reviewer = staff.require(principal);
        PaymentLinkRecognizer.Result result = recognizer.recognise(url);
        if (!result.ok()) {
            throw ApiException.unprocessable("UNSUPPORTED_PAYMENT_LINK",
                "That isn't a Payaza payment link, so it could never be shown anyway.");
        }
        UUID id = links.findByNormalizedUrl(result.normalizedUrl())
            .orElseGet(() -> {
                PaymentLink fresh = new PaymentLink(result.normalizedUrl(), result.provider());
                Instant now = clock.instant();
                fresh.setFirstSeenAt(now);
                fresh.setLastSeenAt(now);
                fresh.setStatusChangedAt(now);
                return links.saveAndFlush(fresh);
            })
            .getId();
        PaymentLink link = links.findForUpdate(id).orElseThrow(ModerationService::linkNotFound);
        if (link.getStatus() != Status.BLOCKED) {
            apply(link, reviewer, Decision.CONFIRM_SCAM, note.strip());
        }
        return detailOf(link);
    }

    /** Where a decision takes a link from its current status; null if it's not an allowed move. */
    static Status target(Decision decision, Status from) {
        return switch (decision) {
            case CONFIRM_SCAM -> from == Status.BLOCKED ? null : Status.BLOCKED;
            case CLEAR -> from == Status.ALLOWED ? null : Status.ALLOWED;
        };
    }

    private void apply(PaymentLink link, User reviewer, Decision decision, String note) {
        Status from = link.getStatus();
        Status to = target(decision, from);
        if (to == null) {
            throw ApiException.conflict("DECISION_NOT_ALLOWED", "This link is already " + from + ".");
        }
        Instant now = clock.instant();
        LinkReview review = reviews.saveAndFlush(
            new LinkReview(link, reviewer, decision, from, to, note, link.getReportCount(), now));
        links.markReportsReviewed(link.getId(), review.getId());
        link.setReportCount(0);
        link.changeStatus(to, CONFIRMED_REASON, now);
        if (to == Status.BLOCKED) {
            give.takeDownEverywhere(link.getId(), now);
        }
        links.flush();
    }

    private LinkDetail detailOf(PaymentLink link) {
        UUID id = link.getId();
        return new LinkDetail(LinkSummary.from(link), link.getBlockedReason(), evidence.reports(id), evidence.rooms(id),
            evidence.gifts(id), reviews.findByPaymentLinkIdOrderByDecidedAtDesc(id).stream().map(LinkDetail.Review::from).toList());
    }

    private static ApiException linkNotFound() {
        return ApiException.notFound("LINK_NOT_FOUND", "There's no payment link with that id.");
    }
}
