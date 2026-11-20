package com.mywatchmate.church;

import com.mywatchmate.common.ApiException;
import com.mywatchmate.give.GiveService;
import com.mywatchmate.give.PaymentLink;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Checks the payment links a church lists: real Payaza links, not blocked, and not another church's. */
@Component
@RequiredArgsConstructor
class ChurchLinks {

    private final GiveService give;
    private final ChurchRepository churches;

    /** @param church the church they're for (its own links are fine); null for a new application */
    Set<PaymentLink> check(List<String> urls, UUID church) {
        Set<PaymentLink> links = new LinkedHashSet<>();
        for (String url : urls) {
            PaymentLink link = give.knownLink(url);
            if (link.isBlocked()) {
                throw ApiException.conflict("LINK_BLOCKED", GiveService.blockedMessage(
                    link.getStatus() == PaymentLink.Status.HELD ? "UNDER_REVIEW" : "BLOCKED_EVERYWHERE")
                    + " (" + link.getNormalizedUrl() + ")");
            }
            churches.findByLink(link.getId())
                .filter(other -> !other.getId().equals(church))
                .ifPresent(other -> {
                    throw ApiException.conflict("LINK_CLAIMED", "Another church registration already has "
                        + link.getNormalizedUrl() + ". If it's yours, contact MyWatchMate support.");
                });
            links.add(link);
        }
        return links;
    }
}
