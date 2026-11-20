package com.mywatchmate.give;

import com.mywatchmate.auth.AuthPrincipal;
import com.mywatchmate.common.ApiException;
import com.mywatchmate.give.dto.GiftMade;
import com.mywatchmate.give.dto.GiftResponse;
import com.mywatchmate.give.dto.GiftResultRequest;
import com.mywatchmate.give.dto.GiveSummary;
import com.mywatchmate.give.dto.GiveSummary.CurrencyTotal;
import com.mywatchmate.give.dto.InRoomCheckout;
import com.mywatchmate.give.dto.Notices;
import com.mywatchmate.give.dto.OpenGiftResponse;
import com.mywatchmate.watch.Participant;
import com.mywatchmate.watch.SpaceAccess;
import com.mywatchmate.watch.SpaceAccess.Membership;
import com.mywatchmate.watch.SpaceEvent;
import com.mywatchmate.watch.SpaceEvents;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Gifts: a viewer taps Give and pays through Payaza. The room hears "Bisi gave 🙏" (or "Someone" when anonymous),
 * never an amount. Two ways:
 * <ul>
 *   <li><b>In the room</b> (the link is one of the room's registered church's own): Payaza's checkout popup opens in
 *       the room with the church's public key and a reference we make. The result comes from Payaza, not the app:
 *       we ask Payaza about that reference when the popup closes, and again every minute until it's settled.</li>
 *   <li><b>In a new tab</b> (any other link): Payaza confirms to the link's owner, not to us, so the result is what
 *       the giver's app reports. Good for "thank you" and personal history, never a financial record.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class GiftService {

    private static final Duration DEFAULT_RANGE = Duration.ofDays(30);
    private static final Duration MAX_RANGE = Duration.ofDays(366);

    private final GiftRepository gifts;
    private final CueDetectionRepository detections;
    private final GiveService give;
    private final SpaceAccess access;
    private final SpaceEvents events;
    private final GiveProperties props;
    private final Clock clock;
    private final CheckoutResolver checkouts;
    private final PayazaClient payaza;
    private final PayazaProperties payazaProps;
    private final TransactionTemplate tx;

    static final int CONFIRM_BATCH = 50;

    @Transactional
    public OpenGiftResponse open(AuthPrincipal principal, UUID spaceId, UUID promptId, boolean anonymous) {
        Membership membership = access.require(principal, spaceId).requireNotArchived();
        CueDetection prompt = give.promptIn(spaceId, promptId);
        if (prompt.getClosedAt() != null || !prompt.getSession().isLive()) {
            throw ApiException.conflict("PROMPT_CLOSED", "This Give prompt has been taken down.");
        }
        PaymentLink link = prompt.getPaymentLink();
        String blocked = give.blockReason(spaceId, link);
        if (blocked != null) {
            throw ApiException.conflict("LINK_BLOCKED", GiveService.blockedMessage(blocked));
        }
        Participant giver = membership.participant();
        Gift gift = new Gift(prompt, prompt.getSession(), giver, link, anonymous);
        gift.setOpenedAt(clock.instant());
        Optional<CheckoutAccount> account = checkouts.resolve(spaceId, link);
        account.ifPresent(a -> gift.useCheckout(newReference(), a));
        Gift saved = gifts.saveAndFlush(gift);
        String merchant = account.map(CheckoutAccount::merchantName).orElse(link.getMerchantName());
        return new OpenGiftResponse(saved.getId(), link.getNormalizedUrl(), link.getProvider(), merchant,
            Notices.paying(merchant), account.map(a -> checkout(a, saved, giver)).orElse(null),
            account.filter(CheckoutAccount::verifiedChurch).map(CheckoutAccount::merchantName).orElse(null));
    }

    /** Ours, unique, and plain enough for any payment system: MWM + 32 hex characters. */
    static String newReference() {
        return "MWM" + UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.ROOT);
    }

    private static InRoomCheckout checkout(CheckoutAccount account, Gift gift, Participant giver) {
        String email = null;
        String first = null;
        String last = null;
        if (giver.getUser() != null) {
            email = giver.getUser().getEmail();
            String[] names = giver.getUser().getDisplayName().strip().split("\\s+", 2);
            first = names[0];
            last = names.length > 1 ? names[1] : null;
        }
        return new InRoomCheckout(account.publicKey(), account.mode().sdkName(), gift.getCheckoutReference(),
            account.currencyCode(), account.merchantName(), email, first, last);
    }

    /** Only whoever opened the gift, once (a late report after it went UNKNOWN is still accepted). */
    @Transactional
    public GiftResponse reportResult(AuthPrincipal principal, UUID giftId, GiftResultRequest result) {
        Gift gift = gifts.findWithGiver(giftId)
            .filter(g -> isCaller(principal, g.getParticipant()))
            .orElseThrow(() -> ApiException.notFound("GIFT_NOT_FOUND", "That gift isn't yours or doesn't exist."));
        if (!gift.awaitsResult()) {
            throw ApiException.conflict("GIFT_ALREADY_REPORTED", "This gift's result was already reported.");
        }
        if (gift.isInRoomCheckout()) {
            return confirmOrNote(gift, result);
        }
        if ((result.amountMinor() == null) != (result.currency() == null)) {
            throw ApiException.badRequest("AMOUNT_NEEDS_CURRENCY", "Send amountMinor and currency together, or neither.");
        }
        String ref = result.providerTxnRef() == null || result.providerTxnRef().isBlank() ? null : result.providerTxnRef().strip();
        if (ref != null && gifts.findByProviderTxnRef(ref).filter(other -> !other.getId().equals(giftId)).isPresent()) {
            throw ApiException.conflict("DUPLICATE_REFERENCE", "That Payaza reference was already reported for another gift.");
        }
        gift.report(Gift.Status.valueOf(result.status().name()), result.amountMinor(), result.currency(), ref, clock.instant());
        thankIfCompleted(gift);
        return GiftResponse.from(gift);
    }

    /**
     * An in-room payment's popup closed. What Payaza says wins; the app's own amount and status are never used for
     * a completed gift. If Payaza doesn't know yet: a giver who cancelled or failed is taken at their word (no money
     * moved); a giver who says it went through stays OPENED until Payaza confirms it (we keep asking).
     */
    private GiftResponse confirmOrNote(Gift gift, GiftResultRequest result) {
        PayazaClient.Payment payment = payaza.status(gift.getCheckoutPublicKey(), gift.getCheckoutMode(), gift.getCheckoutReference());
        if (!settle(gift, payment) && result.status() != GiftResultRequest.Outcome.COMPLETED) {
            gift.report(Gift.Status.valueOf(result.status().name()), null, null, null, clock.instant());
        }
        return GiftResponse.from(gift);
    }

    /** Applies a final answer from Payaza. @return whether there was one */
    private boolean settle(Gift gift, PayazaClient.Payment payment) {
        Gift.Status status = switch (payment.outcome()) {
            case COMPLETED -> Gift.Status.COMPLETED;
            case FAILED -> Gift.Status.FAILED;
            case PENDING, NOT_FOUND -> null;
        };
        if (status == null) {
            return false;
        }
        String reference = payment.payazaReference();
        if (reference != null && gifts.findByProviderTxnRef(reference).filter(other -> !other.getId().equals(gift.getId())).isPresent()) {
            reference = null;
        }
        gift.confirm(status, payment.amountMinor(), payment.currency(), reference, clock.instant());
        thankIfCompleted(gift);
        return true;
    }

    private void thankIfCompleted(Gift gift) {
        if (gift.getStatus() == Gift.Status.COMPLETED) {
            Participant giver = gift.getParticipant();
            events.publish(gift.getSession().getSpace().getId(), SpaceEvent.Type.GIFT_MADE,
                new GiftMade(gift.isAnonymous() ? null : giver.displayName(), gift.isAnonymous()));
        }
    }

    /**
     * Asks Payaza about in-room payments still open. Payaza is called outside any transaction (it can be slow), and
     * each answer is saved in its own. Ones Payaza still hasn't settled after {@code verifyFor} become UNKNOWN.
     * @return how many were settled
     */
    public int confirmPendingCheckouts() {
        Instant since = clock.instant().minus(payazaProps.verifyFor());
        tx.executeWithoutResult(status -> gifts.expireCheckoutsOpenedBefore(since));
        List<Gift> pending = tx.execute(status -> gifts.findCheckoutsAwaitingPayaza(since, Limit.of(CONFIRM_BATCH)));
        int settled = 0;
        for (Gift g : pending == null ? List.<Gift>of() : pending) {
            PayazaClient.Payment payment = payaza.status(g.getCheckoutPublicKey(), g.getCheckoutMode(), g.getCheckoutReference());
            Boolean done = tx.execute(status -> gifts.findWithGiver(g.getId())
                .filter(fresh -> fresh.getStatus() == Gift.Status.OPENED)
                .map(fresh -> settle(fresh, payment))
                .orElse(false));
            if (Boolean.TRUE.equals(done)) {
                settled++;
            }
        }
        return settled;
    }

    @Transactional(readOnly = true)
    public List<GiftResponse> mine(UUID userId) {
        return gifts.findForUser(userId).stream().map(GiftResponse::from).toList();
    }

    /** Owner and admins: how giving went over a period. Counts and totals only, never who gave what. */
    @Transactional(readOnly = true)
    public GiveSummary summary(AuthPrincipal principal, UUID spaceId, Instant from, Instant to) {
        access.require(principal, spaceId).requireOwnerOrAdmin();
        Instant end = to != null ? to : clock.instant();
        Instant start = from != null ? from : end.minus(DEFAULT_RANGE);
        if (!start.isBefore(end) || Duration.between(start, end).compareTo(MAX_RANGE) > 0) {
            throw ApiException.badRequest("INVALID_RANGE", "'from' must be before 'to', and at most a year apart.");
        }
        List<Gift> found = gifts.findInSpace(spaceId, start, end);
        long completed = 0;
        long notCompleted = 0;
        long unreported = 0;
        long confirmed = 0;
        boolean selfReported = false;
        Map<String, long[]> totals = new TreeMap<>();
        for (Gift g : found) {
            switch (g.getStatus()) {
                case COMPLETED -> {
                    completed++;
                    if (g.isVerified()) {
                        confirmed++;
                    } else {
                        selfReported = true;
                    }
                    if (g.getAmountMinor() != null) {
                        long[] t = totals.computeIfAbsent(g.getCurrency(), c -> new long[2]);
                        t[0] += g.getAmountMinor();
                        t[1]++;
                    }
                }
                case FAILED, CANCELLED -> notCompleted++;
                case OPENED, UNKNOWN -> unreported++;
            }
        }
        List<CurrencyTotal> byCurrency = totals.entrySet().stream()
            .map(e -> new CurrencyTotal(e.getKey(), e.getValue()[0], e.getValue()[1]))
            .toList();
        return new GiveSummary(start, end, detections.countShownInSpace(spaceId, start, end), found.size(),
            completed, notCompleted, unreported, byCurrency, selfReported, confirmed);
    }

    /** Opened gifts nobody reported a result for in time become UNKNOWN. Call inside a transaction. */
    public int expireUnreported() {
        return gifts.expireOpenedBefore(clock.instant().minus(props.giftExpiry()));
    }

    private static boolean isCaller(AuthPrincipal principal, Participant giver) {
        return principal.isGuest()
            ? giver.getId().equals(principal.participantId())
            : giver.getUser() != null && giver.getUser().getId().equals(principal.userId());
    }
}
