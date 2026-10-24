package com.mywatchmate.give;

import com.mywatchmate.auth.AuthPrincipal;
import com.mywatchmate.common.ApiException;
import com.mywatchmate.give.dto.GiftMade;
import com.mywatchmate.give.dto.GiftResponse;
import com.mywatchmate.give.dto.GiftResultRequest;
import com.mywatchmate.give.dto.GiveSummary;
import com.mywatchmate.give.dto.GiveSummary.CurrencyTotal;
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
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Gifts: a viewer taps Give, their app opens Payaza's checkout in the room, and reports what it said.
 * Payaza confirms payments to the link's owner, not to us, so results are self-reported: good for
 * "thank you" and personal history, never treated as a financial record. The room hears "Bisi gave 🙏"
 * (or "Someone" when anonymous), never an amount.
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
        Gift gift = new Gift(prompt, prompt.getSession(), membership.participant(), link, anonymous);
        gift.setOpenedAt(clock.instant());
        gift = gifts.saveAndFlush(gift);
        return new OpenGiftResponse(gift.getId(), link.getNormalizedUrl(), link.getProvider(), link.getMerchantName(),
            Notices.paying(link));
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
        if ((result.amountMinor() == null) != (result.currency() == null)) {
            throw ApiException.badRequest("AMOUNT_NEEDS_CURRENCY", "Send amountMinor and currency together, or neither.");
        }
        String ref = result.providerTxnRef() == null || result.providerTxnRef().isBlank() ? null : result.providerTxnRef().strip();
        if (ref != null && gifts.findByProviderTxnRef(ref).filter(other -> !other.getId().equals(giftId)).isPresent()) {
            throw ApiException.conflict("DUPLICATE_REFERENCE", "That Payaza reference was already reported for another gift.");
        }
        gift.report(Gift.Status.valueOf(result.status().name()), result.amountMinor(), result.currency(), ref, clock.instant());
        if (gift.getStatus() == Gift.Status.COMPLETED) {
            Participant giver = gift.getParticipant();
            events.publish(gift.getSession().getSpace().getId(), SpaceEvent.Type.GIFT_MADE,
                new GiftMade(gift.isAnonymous() ? null : giver.displayName(), gift.isAnonymous()));
        }
        return GiftResponse.from(gift);
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
        Map<String, long[]> totals = new TreeMap<>();
        for (Gift g : found) {
            switch (g.getStatus()) {
                case COMPLETED -> {
                    completed++;
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
            completed, notCompleted, unreported, byCurrency, true);
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
