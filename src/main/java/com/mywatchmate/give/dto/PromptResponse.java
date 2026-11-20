package com.mywatchmate.give.dto;

import com.mywatchmate.give.CueDetection;
import com.mywatchmate.give.CheckoutAccount;
import com.mywatchmate.give.PaymentLink;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

@Schema(description = "A Give button showing in the room. Show `notice` on the card.")
public record PromptResponse(
    @Schema(description = "The prompt id; use it to open a gift") UUID id,
    UUID sessionId,
    @Schema(example = "https://business.payaza.africa/pay/stmarks") String url,
    PaymentLink.Provider provider,
    @Schema(description = "Who the money goes to, when known", nullable = true) String merchantName,
    @Schema(description = "AI: spotted in the stream. HOST: put up by a host.") CueDetection.Source source,
    Instant shownAt,
    @Schema(description = "Hide the card after this") Instant expiresAt,
    @Schema(example = "You're paying the link's owner through Payaza. MyWatchMate never holds your money.") String notice,
    @Schema(description = "Giving happens inside the room (Payaza's popup) and Payaza confirms it") boolean inRoomCheckout,
    @Schema(description = "A church MyWatchMate verified: show a \"✓ Verified\" badge with this name", nullable = true)
    String verifiedChurch
) {
    public static PromptResponse from(CueDetection d) {
        return from(d, null);
    }

    /** With where the money goes when it's paid in the room (a verified church, or the room's own account). */
    public static PromptResponse from(CueDetection d, CheckoutAccount account) {
        PaymentLink link = d.getPaymentLink();
        String merchant = account != null ? account.merchantName() : link.getMerchantName();
        return new PromptResponse(d.getId(), d.getSession().getId(), link.getNormalizedUrl(), link.getProvider(),
            merchant, d.getSource(), d.getDetectedAt(), d.getExpiresAt(), Notices.paying(merchant), account != null,
            account != null && account.verifiedChurch() ? account.merchantName() : null);
    }
}
