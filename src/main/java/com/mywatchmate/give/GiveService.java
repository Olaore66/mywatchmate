package com.mywatchmate.give;

import com.mywatchmate.auth.AuthPrincipal;
import com.mywatchmate.common.ApiException;
import com.mywatchmate.give.dto.BlockLinkRequest;
import com.mywatchmate.give.dto.BlockedLinkResponse;
import com.mywatchmate.give.dto.CueOutcome;
import com.mywatchmate.give.dto.InternalCueRequest;
import com.mywatchmate.give.dto.PromptResponse;
import com.mywatchmate.watch.SpaceAccess;
import com.mywatchmate.watch.SpaceAccess.Membership;
import com.mywatchmate.watch.SpaceEvent;
import com.mywatchmate.watch.SpaceEvents;
import com.mywatchmate.watch.WatchSession;
import com.mywatchmate.watch.WatchSessionRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns a payment link into a Give prompt in a live session. Every source (the AI worker, a host by hand)
 * goes through the same checks: a real Payaza link, not blocked everywhere or in this room, not reported
 * too often. One prompt shows at a time per session; a newer one replaces it. Also room blocks and
 * "looks like a scam" reports.
 */
@Service
@RequiredArgsConstructor
public class GiveService {

    static final String REPORTED_REASON = "Reported by viewers; under review";

    private final PaymentLinkRecognizer recognizer;
    private final PaymentLinkRepository links;
    private final CueDetectionRepository detections;
    private final SpaceBlockedLinkRepository roomBlocks;
    private final WatchSessionRepository sessions;
    private final SpaceAccess access;
    private final SpaceEvents events;
    private final GiveProperties props;
    private final Clock clock;

    /** A host puts up the room's giving link by hand. Problems are errors (nothing is recorded). */
    @Transactional
    public PromptResponse showByHost(AuthPrincipal principal, UUID spaceId, String url) {
        Membership membership = access.require(principal, spaceId).requireOwnerOrAdmin().requireNotArchived();
        WatchSession live = sessions.findFirstBySpaceIdAndStatus(spaceId, WatchSession.Status.LIVE)
            .orElseThrow(() -> ApiException.conflict("NO_LIVE_SESSION",
                "Give prompts show during a live session. Start one first with Watch now."));
        PaymentLink link = linkFor(recognised(url), clock.instant());
        String blocked = blockReason(spaceId, link);
        if (blocked != null) {
            throw ApiException.conflict("LINK_BLOCKED", blockedMessage(blocked));
        }
        CueDetection prompt = new CueDetection(live, CueDetection.CueType.LINK, url.strip(), CueDetection.Status.SHOWN);
        prompt.setSource(CueDetection.Source.HOST);
        prompt.setShownBy(membership.participant());
        return show(prompt, link, clock.instant());
    }

    /**
     * A cue from the AI worker. Every outcome is recorded with its reason, except the same link seen again
     * soon after (the AI sees a QR code on screen for minutes; that would flood the table).
     */
    @Transactional
    public CueOutcome fromWorker(InternalCueRequest request) {
        WatchSession session = sessions.findById(request.sessionId())
            .filter(WatchSession::isLive)
            .orElseThrow(() -> ApiException.conflict("SESSION_NOT_LIVE", "That session isn't live."));
        Instant now = clock.instant();
        CueDetection cue = new CueDetection(session,
            request.cueType() != null ? request.cueType() : CueDetection.CueType.QR, request.rawValue(), null);
        cue.setConfidence(request.confidence());
        cue.setContentPositionSeconds(request.positionSeconds());
        cue.setDetectedAt(now);

        PaymentLinkRecognizer.Result result = recognizer.recognise(request.rawValue());
        if (!result.ok()) {
            return record(cue, CueDetection.Status.REJECTED, result.reason());
        }
        PaymentLink link = linkFor(result, now);
        cue.setPaymentLink(link);
        UUID spaceId = session.getSpace().getId();
        String blocked = blockReason(spaceId, link);
        if (blocked != null) {
            return record(cue, CueDetection.Status.BLOCKED, blocked);
        }
        if (detections.existsBySessionIdAndPaymentLinkIdAndStatusAndDetectedAtAfter(session.getId(), link.getId(),
                CueDetection.Status.SHOWN, now.minus(props.repromptWindow()))) {
            return new CueOutcome(null, CueDetection.Status.REJECTED, "RECENTLY_SHOWN");
        }
        PromptResponse shown = show(cue, link, now);
        return new CueOutcome(shown.id(), CueDetection.Status.SHOWN, null);
    }

    /** What's showing now, for someone arriving late. */
    @Transactional(readOnly = true)
    public Optional<PromptResponse> current(AuthPrincipal principal, UUID spaceId) {
        access.require(principal, spaceId);
        Instant now = clock.instant();
        return sessions.findFirstBySpaceIdAndStatus(spaceId, WatchSession.Status.LIVE)
            .flatMap(live -> detections.findBySessionIdAndStatusAndClosedAtIsNull(live.getId(), CueDetection.Status.SHOWN)
                .stream()
                .filter(d -> d.isShowing(now))
                .max(Comparator.comparing(CueDetection::getDetectedAt)))
            .map(PromptResponse::from);
    }

    /** Owner or admins take the card down for everyone. */
    @Transactional
    public void close(AuthPrincipal principal, UUID spaceId, UUID promptId) {
        access.require(principal, spaceId).requireOwnerOrAdmin();
        CueDetection prompt = promptIn(spaceId, promptId);
        if (prompt.getClosedAt() == null) {
            takeDown(prompt, spaceId, clock.instant());
        }
    }

    // ---- room blocks ------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<BlockedLinkResponse> blockedLinks(AuthPrincipal principal, UUID spaceId) {
        access.require(principal, spaceId).requireOwnerOrAdmin();
        return roomBlocks.findForSpace(spaceId).stream().map(BlockedLinkResponse::from).toList();
    }

    /** Blocking also takes down the link's prompt if it's showing in this room. */
    @Transactional
    public BlockedLinkResponse block(AuthPrincipal principal, UUID spaceId, BlockLinkRequest request) {
        Membership membership = access.require(principal, spaceId).requireOwnerOrAdmin();
        Instant now = clock.instant();
        PaymentLink link = linkFor(recognised(request.url()), now);
        SpaceBlockedLinkId id = new SpaceBlockedLinkId(spaceId, link.getId());
        SpaceBlockedLink blocked = roomBlocks.findById(id).orElseGet(() -> {
            SpaceBlockedLink fresh = new SpaceBlockedLink(membership.space(), link, membership.participant(),
                blankToNull(request.reason()));
            fresh.setBlockedAt(now);
            return roomBlocks.saveAndFlush(fresh);
        });
        detections.findOpenInSpaceForLink(spaceId, link.getId()).forEach(p -> takeDown(p, spaceId, now));
        return BlockedLinkResponse.from(blocked);
    }

    @Transactional
    public void unblock(AuthPrincipal principal, UUID spaceId, UUID paymentLinkId) {
        access.require(principal, spaceId).requireOwnerOrAdmin();
        roomBlocks.findById(new SpaceBlockedLinkId(spaceId, paymentLinkId)).ifPresent(roomBlocks::delete);
    }

    // ---- reports ----------------------------------------------------------------------------------

    /**
     * "This looks like a scam." Each person counts once per link (a user across all their rooms). Enough
     * reports block the link everywhere until we review it, and take its prompts down at once.
     */
    @Transactional
    public void report(AuthPrincipal principal, UUID spaceId, UUID promptId, String reason) {
        access.require(principal, spaceId);
        CueDetection prompt = promptIn(spaceId, promptId);
        PaymentLink link = prompt.getPaymentLink();
        String reporter = principal.isGuest() ? "guest:" + principal.participantId() : "user:" + principal.userId();
        if (links.insertReport(link.getId(), reporter, prompt.getId(), blankToNull(reason)) == 0) {
            return;
        }
        link.setReportCount(link.getReportCount() + 1);
        if (link.getReportCount() >= props.reportThreshold() && !link.isBlocked()) {
            link.block(REPORTED_REASON);
            Instant now = clock.instant();
            detections.findOpenForLink(link.getId())
                .forEach(p -> takeDown(p, p.getSession().getSpace().getId(), now));
        }
    }

    /** Why this link mustn't be shown in this room, or null. */
    public String blockReason(UUID spaceId, PaymentLink link) {
        if (link.isBlocked()) {
            return "BLOCKED_EVERYWHERE";
        }
        if (roomBlocks.existsById(new SpaceBlockedLinkId(spaceId, link.getId()))) {
            return "BLOCKED_IN_ROOM";
        }
        return null;
    }

    static String blockedMessage(String reason) {
        return "BLOCKED_IN_ROOM".equals(reason)
            ? "This payment link is blocked in this room."
            : "This payment link has been blocked on MyWatchMate.";
    }

    CueDetection promptIn(UUID spaceId, UUID promptId) {
        return detections.findInSpace(promptId, spaceId)
            .filter(d -> d.getStatus() == CueDetection.Status.SHOWN)
            .orElseThrow(() -> ApiException.notFound("PROMPT_NOT_FOUND", "That Give prompt isn't in this room."));
    }

    private PaymentLinkRecognizer.Result recognised(String url) {
        PaymentLinkRecognizer.Result result = recognizer.recognise(url);
        if (!result.ok()) {
            throw ApiException.unprocessable("UNSUPPORTED_PAYMENT_LINK",
                "That isn't a Payaza payment link. Only https Payaza links can be shown.");
        }
        return result;
    }

    private PaymentLink linkFor(PaymentLinkRecognizer.Result result, Instant now) {
        PaymentLink link = links.findByNormalizedUrl(result.normalizedUrl())
            .orElseGet(() -> {
                PaymentLink fresh = new PaymentLink(result.normalizedUrl(), result.provider());
                fresh.setFirstSeenAt(now);
                return links.saveAndFlush(fresh);
            });
        link.setLastSeenAt(now);
        return link;
    }

    /** Replaces whatever was showing in the session and tells the room. */
    private PromptResponse show(CueDetection prompt, PaymentLink link, Instant now) {
        UUID sessionId = prompt.getSession().getId();
        detections.findBySessionIdAndStatusAndClosedAtIsNull(sessionId, CueDetection.Status.SHOWN)
            .forEach(old -> old.close(now));
        prompt.setPaymentLink(link);
        prompt.setStatus(CueDetection.Status.SHOWN);
        prompt.setDetectedAt(now);
        prompt.setExpiresAt(now.plus(props.promptLifetime()));
        PromptResponse response = PromptResponse.from(detections.saveAndFlush(prompt));
        events.publish(prompt.getSession().getSpace().getId(), SpaceEvent.Type.GIVE_PROMPT, response);
        return response;
    }

    private CueOutcome record(CueDetection cue, CueDetection.Status status, String reason) {
        cue.setStatus(status);
        cue.setRejectReason(reason);
        return new CueOutcome(detections.saveAndFlush(cue).getId(), status, reason);
    }

    private void takeDown(CueDetection prompt, UUID spaceId, Instant now) {
        prompt.close(now);
        events.publish(spaceId, SpaceEvent.Type.GIVE_PROMPT_CLOSED, Map.of("promptId", prompt.getId()));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
