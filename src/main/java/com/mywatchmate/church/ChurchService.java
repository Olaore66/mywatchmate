package com.mywatchmate.church;

import com.mywatchmate.auth.AuthPrincipal;
import com.mywatchmate.church.dto.ChurchApplicationRequest;
import com.mywatchmate.church.dto.ChurchChangeRequest;
import com.mywatchmate.church.dto.ChurchChangeResponse;
import com.mywatchmate.church.dto.ChurchResponse;
import com.mywatchmate.common.ApiException;
import com.mywatchmate.give.PaymentLink;
import com.mywatchmate.identity.User;
import com.mywatchmate.identity.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The church's side: applying to be verified, seeing how it's going, and asking to change the key or links later.
 * Nothing here makes a church usable; only staff can (see {@link ChurchReviewService}).
 */
@Service
@RequiredArgsConstructor
public class ChurchService {

    static final int MAX_PENDING_PER_PERSON = 3;

    private final ChurchRepository churches;
    private final ChurchChangeRepository changes;
    private final ChurchLinks links;
    private final UserRepository users;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    @Transactional
    public ChurchResponse apply(AuthPrincipal principal, ChurchApplicationRequest request) {
        User applicant = user(principal);
        if (churches.countByAppliedByIdAndStatus(applicant.getId(), Church.Status.PENDING) >= MAX_PENDING_PER_PERSON) {
            throw ApiException.conflict("TOO_MANY_APPLICATIONS",
                "You already have " + MAX_PENDING_PER_PERSON + " applications waiting. We'll be in touch about them first.");
        }
        Instant now = clock.instant();
        Church church = new Church(request.name().strip(), applicant, now);
        church.setWebsite(blankToNull(request.website()));
        church.setOfficialEmail(blankToNull(request.officialEmail()));
        church.setOfficialPhone(blankToNull(request.officialPhone()));
        church.setPublicKey(request.publicKey().strip());
        church.setMode(request.mode());
        church.setCurrencyCode(request.currencyCode() == null ? "NGN" : request.currencyCode());
        church.getLinks().addAll(links.check(request.paymentLinks(), null));
        try {
            church = churches.saveAndFlush(church);
        } catch (DataIntegrityViolationException e) {
            throw ApiException.conflict("LINK_CLAIMED", "One of those links was just registered by another church.");
        }
        events.publishEvent(new ChurchEvents.Applied(church.getId()));
        return ChurchResponse.from(church);
    }

    @Transactional(readOnly = true)
    public List<ChurchResponse> mine(UUID userId) {
        return churches.findAppliedBy(userId).stream().map(ChurchResponse::from).toList();
    }

    /** A verified (or suspended) church's new key or links. It keeps the current ones until staff approve. */
    @Transactional
    public ChurchChangeResponse requestChange(AuthPrincipal principal, UUID churchId, ChurchChangeRequest request) {
        User requester = user(principal);
        Church church = churches.findWithLinks(churchId)
            .filter(c -> c.getAppliedBy() != null && c.getAppliedBy().getId().equals(requester.getId()))
            .orElseThrow(() -> ApiException.notFound("CHURCH_NOT_FOUND", "You don't manage a church with that id."));
        if (church.getStatus() != Church.Status.VERIFIED && church.getStatus() != Church.Status.SUSPENDED) {
            throw ApiException.conflict("CHURCH_NOT_VERIFIED", "Changes are for verified churches. Apply again instead.");
        }
        if (request.isEmpty()) {
            throw ApiException.badRequest("NOTHING_TO_CHANGE", "Send a new publicKey, mode or paymentLinks.");
        }
        if (changes.findByChurchIdAndStatus(churchId, ChurchChange.Status.PENDING).isPresent()) {
            throw ApiException.conflict("CHANGE_PENDING", "A change is already waiting for review.");
        }
        List<String> newLinks = request.paymentLinks() == null ? null
            : links.check(request.paymentLinks(), churchId).stream().map(PaymentLink::getNormalizedUrl).toList();
        ChurchChange change = changes.saveAndFlush(new ChurchChange(church,
            request.publicKey() == null ? null : request.publicKey().strip(), request.mode(), newLinks, requester, clock.instant()));
        events.publishEvent(new ChurchEvents.ChangeRequested(churchId));
        return ChurchChangeResponse.from(change);
    }

    private User user(AuthPrincipal principal) {
        if (principal.isGuest()) {
            throw ApiException.forbidden("FORBIDDEN", "Sign in with an account to register a church.");
        }
        return users.findById(principal.userId())
            .orElseThrow(() -> ApiException.notFound("USER_NOT_FOUND", "Your account no longer exists."));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
