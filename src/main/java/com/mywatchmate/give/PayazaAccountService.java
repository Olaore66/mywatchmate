package com.mywatchmate.give;

import com.mywatchmate.auth.AuthPrincipal;
import com.mywatchmate.common.ApiException;
import com.mywatchmate.give.dto.PayazaAccountRequest;
import com.mywatchmate.give.dto.PayazaAccountResponse;
import com.mywatchmate.watch.SpaceAccess;
import com.mywatchmate.watch.SpaceAccess.Membership;
import java.time.Clock;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A room's church registers its Payaza account so givers can pay inside the room. Owner and admins only. The
 * registration applies to this room only, and only to the links listed: a guest preacher's charity link shown in
 * the same room still opens in a new tab, and nobody can put their key behind another room's link.
 */
@Service
@RequiredArgsConstructor
public class PayazaAccountService {

    private final PayazaAccountRepository accounts;
    private final GiveService give;
    private final SpaceAccess access;
    private final VerifiedChurches churches;
    private final Clock clock;

    @Transactional(readOnly = true)
    public Optional<PayazaAccountResponse> get(AuthPrincipal principal, UUID spaceId) {
        access.require(principal, spaceId).requireOwnerOrAdmin();
        return accounts.findWithLinks(spaceId).map(PayazaAccountResponse::from);
    }

    /** Creates or replaces the room's registration. */
    @Transactional
    public PayazaAccountResponse save(AuthPrincipal principal, UUID spaceId, PayazaAccountRequest request) {
        Membership membership = access.require(principal, spaceId).requireOwnerOrAdmin().requireNotArchived();
        Set<PaymentLink> links = new LinkedHashSet<>();
        for (String url : request.paymentLinks()) {
            PaymentLink link = give.knownLink(url);
            String blocked = give.blockReason(spaceId, link);
            if (blocked != null) {
                throw ApiException.conflict("LINK_BLOCKED", GiveService.blockedMessage(blocked) + " (" + link.getNormalizedUrl() + ")");
            }
            if (churches.claims(link.getId())) {
                throw ApiException.conflict("LINK_BELONGS_TO_CHURCH", "That link belongs to a church registered with "
                    + "MyWatchMate (" + link.getNormalizedUrl() + "). Givers already pay it inside the room once it's verified.");
            }
            links.add(link);
        }
        PayazaAccount account = accounts.findWithLinks(spaceId).orElseGet(() -> new PayazaAccount(spaceId));
        account.setPublicKey(request.publicKey().strip());
        account.setMode(request.mode());
        account.setMerchantName(request.merchantName().strip());
        account.setCurrencyCode(request.currencyCode() == null ? "NGN" : request.currencyCode());
        account.getLinks().clear();
        account.getLinks().addAll(links);
        account.setUpdatedBy(membership.participant());
        account.setUpdatedAt(clock.instant());
        return PayazaAccountResponse.from(accounts.saveAndFlush(account));
    }

    /** Givers in this room go back to opening the links in a new tab. Gifts already made are kept. */
    @Transactional
    public void remove(AuthPrincipal principal, UUID spaceId) {
        access.require(principal, spaceId).requireOwnerOrAdmin();
        accounts.findById(spaceId).ifPresent(accounts::delete);
    }
}
