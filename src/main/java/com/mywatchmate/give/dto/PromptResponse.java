package com.mywatchmate.give.dto;

import com.mywatchmate.give.CueDetection;
import com.mywatchmate.give.PaymentLink;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

@Schema(description = "A Give button showing in the room. Show `notice` on the card.")
public record PromptResponse(
    @Schema(description = "The prompt id; use it to open a gift") UUID id,
    UUID sessionId,
    @Schema(example = "https://pay.payaza.africa/give/stmarks") String url,
    PaymentLink.Provider provider,
    @Schema(description = "Who the money goes to, when known", nullable = true) String merchantName,
    @Schema(description = "AI: spotted in the stream. HOST: put up by a host.") CueDetection.Source source,
    Instant shownAt,
    @Schema(description = "Hide the card after this") Instant expiresAt,
    @Schema(example = "You're paying the link's owner through Payaza. MyWatchMate never holds your money.") String notice
) {
    public static PromptResponse from(CueDetection d) {
        PaymentLink link = d.getPaymentLink();
        return new PromptResponse(d.getId(), d.getSession().getId(), link.getNormalizedUrl(), link.getProvider(),
            link.getMerchantName(), d.getSource(), d.getDetectedAt(), d.getExpiresAt(), Notices.paying(link));
    }
}
