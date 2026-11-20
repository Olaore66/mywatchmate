package com.mywatchmate.church;

import com.mywatchmate.auth.AuthPrincipal;
import com.mywatchmate.church.dto.ChangeDecisionRequest;
import com.mywatchmate.church.dto.ChurchChangeResponse;
import com.mywatchmate.church.dto.ChurchDecisionRequest;
import com.mywatchmate.church.dto.ChurchDetail;
import com.mywatchmate.church.dto.ChurchQueue;
import com.mywatchmate.church.dto.ChurchResponse;
import com.mywatchmate.church.dto.TestCheckoutResult;
import com.mywatchmate.common.ApiException;
import com.mywatchmate.give.PayazaAccount;
import com.mywatchmate.give.PayazaClient;
import com.mywatchmate.give.dto.InRoomCheckout;
import com.mywatchmate.identity.User;
import com.mywatchmate.moderation.StaffAccess;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Limit;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Staff decide which churches are real. Approving a church decides where money goes in every room, so: staff only
 * (checked in the database every call), the church is locked while deciding, the decision only applies to the
 * status the reviewer saw, every decision is kept for good, and a test payment proves whose the key is.
 * <pre>
 *   PENDING --APPROVE--> VERIFIED --SUSPEND--> SUSPENDED --REINSTATE--> VERIFIED
 *   PENDING --REJECT---> REJECTED (its links are freed for the real church)
 * </pre>
 */
@Service
@RequiredArgsConstructor
public class ChurchReviewService {

    static final int MAX_LIST = 200;
    static final List<String> CHECKLIST = List.of(
        "Contact the church through channels you found yourself (its real website or social media), not the ones in the application.",
        "Make a ₦100 test payment with the submitted key: Payaza's popup and receipt must show the church's name.",
        "Ask the church to confirm the ₦100 arrived in its Payaza account.",
        "Open each payment link: Payaza's page must show the same business name.");

    private final StaffAccess staff;
    private final ChurchRepository churches;
    private final ChurchChangeRepository changes;
    private final ChurchReviewRepository reviews;
    private final ChurchLinks links;
    private final PayazaClient payaza;
    private final JdbcClient jdbc;
    private final Clock clock;

    @Transactional(readOnly = true)
    public ChurchQueue list(AuthPrincipal principal, Church.Status status) {
        staff.require(principal);
        List<ChurchResponse> found = churches.findByStatusOldestFirst(status == null ? Church.Status.PENDING : status,
            Limit.of(MAX_LIST)).stream().map(ChurchResponse::from).toList();
        return new ChurchQueue(found, churches.countByStatus(Church.Status.PENDING),
            changes.countByStatus(ChurchChange.Status.PENDING));
    }

    @Transactional(readOnly = true)
    public ChurchDetail detail(AuthPrincipal principal, UUID churchId) {
        staff.require(principal);
        return detailOf(churches.findWithLinks(churchId).orElseThrow(ChurchReviewService::notFound));
    }

    @Transactional
    public ChurchDetail decide(AuthPrincipal principal, UUID churchId, ChurchDecisionRequest request) {
        User reviewer = staff.require(principal);
        Church church = churches.findForUpdate(churchId).orElseThrow(ChurchReviewService::notFound);
        Church.Status from = church.getStatus();
        if (from != request.expectedStatus()) {
            throw ApiException.conflict("CHURCH_STATUS_CHANGED",
                "This church is now " + from + ": someone decided while you were looking. Reload it.");
        }
        Church.Status to = target(request.decision(), from);
        if (to == null) {
            throw ApiException.conflict("DECISION_NOT_ALLOWED", "You can't " + request.decision().name().toLowerCase(Locale.ROOT)
                + " a church that is " + from + ".");
        }
        Instant now = clock.instant();
        church.changeStatus(to, now);
        if (request.decision() == ChurchDecisionRequest.Decision.APPROVE) {
            church.setVerifiedBy(reviewer);
            church.setVerifiedAt(now);
        }
        if (to == Church.Status.REJECTED) {
            church.getLinks().clear(); // free them for the real church
        }
        reviews.save(new ChurchReview(church, reviewer, ChurchReview.Decision.valueOf(request.decision().name()), from, to,
            request.note().strip(), now));
        churches.flush();
        return detailOf(churches.findWithLinks(churchId).orElseThrow(ChurchReviewService::notFound));
    }

    /** Where a decision takes a church; null if it's not an allowed move. */
    static Church.Status target(ChurchDecisionRequest.Decision decision, Church.Status from) {
        return switch (decision) {
            case APPROVE -> from == Church.Status.PENDING ? Church.Status.VERIFIED : null;
            case REJECT -> from == Church.Status.PENDING ? Church.Status.REJECTED : null;
            case SUSPEND -> from == Church.Status.VERIFIED ? Church.Status.SUSPENDED : null;
            case REINSTATE -> from == Church.Status.SUSPENDED ? Church.Status.VERIFIED : null;
        };
    }

    /** A church's new key or links take effect only now. */
    @Transactional
    public ChurchDetail decideChange(AuthPrincipal principal, UUID changeId, ChangeDecisionRequest request) {
        User reviewer = staff.require(principal);
        ChurchChange change = changes.findForUpdate(changeId)
            .filter(c -> c.getStatus() == ChurchChange.Status.PENDING)
            .orElseThrow(() -> ApiException.notFound("CHANGE_NOT_FOUND", "No change waiting with that id."));
        Church church = churches.findForUpdate(change.getChurch().getId()).orElseThrow(ChurchReviewService::notFound);
        Instant now = clock.instant();
        boolean approve = request.decision() == ChangeDecisionRequest.Decision.APPROVE;
        if (approve) {
            if (change.getPublicKey() != null) {
                church.setPublicKey(change.getPublicKey());
            }
            if (change.getMode() != null) {
                church.setMode(change.getMode());
            }
            if (change.getPaymentLinks() != null) {
                var newLinks = links.check(change.getPaymentLinks(), church.getId());
                church.getLinks().clear();
                churches.flush();
                church.getLinks().addAll(newLinks);
            }
        }
        change.setStatus(approve ? ChurchChange.Status.APPROVED : ChurchChange.Status.REJECTED);
        change.setDecidedAt(now);
        reviews.save(new ChurchReview(church, reviewer, approve ? ChurchReview.Decision.APPROVE_CHANGE
            : ChurchReview.Decision.REJECT_CHANGE, church.getStatus(), church.getStatus(), request.note().strip(), now));
        churches.flush();
        return detailOf(churches.findWithLinks(church.getId()).orElseThrow(ChurchReviewService::notFound));
    }

    /**
     * A checkout for staff to pay ₦100 with the church's key (or the key in its pending change), proving whose it is:
     * Payaza shows the account's business name, and the church can confirm the money arrived.
     */
    @Transactional(readOnly = true)
    public InRoomCheckout testCheckout(AuthPrincipal principal, UUID churchId, boolean pendingKey) {
        User reviewer = staff.require(principal);
        Church church = churches.findById(churchId).orElseThrow(ChurchReviewService::notFound);
        PaymentKey key = keyFor(church, pendingKey);
        String[] names = reviewer.getDisplayName().strip().split("\\s+", 2);
        return new InRoomCheckout(key.publicKey(), key.mode().sdkName(), "MWMTEST" + UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.ROOT)
            .substring(0, 28), church.getCurrencyCode(), church.getName(), reviewer.getEmail(), names[0],
            names.length > 1 ? names[1] : null);
    }

    /** What Payaza says about that test payment. */
    public TestCheckoutResult testCheckoutResult(AuthPrincipal principal, UUID churchId, String reference, boolean pendingKey) {
        staff.require(principal);
        if (!reference.matches("MWMTEST[0-9A-F]{28}")) {
            throw ApiException.badRequest("INVALID_REFERENCE", "That isn't a test reference we gave you.");
        }
        Church church = churches.findById(churchId).orElseThrow(ChurchReviewService::notFound);
        PaymentKey key = keyFor(church, pendingKey);
        PayazaClient.Payment payment = payaza.status(key.publicKey(), key.mode(), reference);
        return new TestCheckoutResult(payment.outcome(), payment.amountMinor(), payment.currency(), payment.payazaReference());
    }

    private record PaymentKey(String publicKey, PayazaAccount.Mode mode) {
    }

    /** The church's key, or the one in its pending change (to test before approving it). */
    private PaymentKey keyFor(Church church, boolean pendingKey) {
        if (!pendingKey) {
            return new PaymentKey(church.getPublicKey(), church.getMode());
        }
        ChurchChange change = changes.findByChurchIdAndStatus(church.getId(), ChurchChange.Status.PENDING)
            .orElseThrow(() -> ApiException.notFound("CHANGE_NOT_FOUND", "This church has no change waiting."));
        return new PaymentKey(change.getPublicKey() != null ? change.getPublicKey() : church.getPublicKey(),
            change.getMode() != null ? change.getMode() : church.getMode());
    }

    private ChurchDetail detailOf(Church church) {
        User applicant = church.getAppliedBy();
        return new ChurchDetail(ChurchResponse.from(church),
            applicant == null ? null : new ChurchDetail.Applicant(applicant.getId(), applicant.getDisplayName(), applicant.getEmail()),
            changes.findByChurchIdAndStatus(church.getId(), ChurchChange.Status.PENDING).map(ChurchChangeResponse::from).orElse(null),
            gifts(church.getId()),
            reviews.findByChurchIdOrderByDecidedAtDesc(church.getId()).stream().map(ChurchDetail.Review::from).toList(),
            CHECKLIST);
    }

    private ChurchDetail.GiftCounts gifts(UUID churchId) {
        return jdbc.sql("""
                SELECT count(*) FILTER (WHERE status = 'OPENED') AS opened,
                       count(*) FILTER (WHERE status = 'COMPLETED') AS completed,
                       count(*) FILTER (WHERE status = 'FAILED') AS failed,
                       count(*) FILTER (WHERE status = 'CANCELLED') AS cancelled,
                       count(*) FILTER (WHERE status = 'UNKNOWN') AS unknown
                FROM gifts WHERE church_id = :id
                """)
            .param("id", churchId)
            .query((rs, i) -> new ChurchDetail.GiftCounts(rs.getLong("opened"), rs.getLong("completed"), rs.getLong("failed"),
                rs.getLong("cancelled"), rs.getLong("unknown")))
            .single();
    }

    private static ApiException notFound() {
        return ApiException.notFound("CHURCH_NOT_FOUND", "There's no church with that id.");
    }
}
