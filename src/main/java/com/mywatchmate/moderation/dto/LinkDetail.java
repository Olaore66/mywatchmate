package com.mywatchmate.moderation.dto;

import com.mywatchmate.give.PaymentLink;
import com.mywatchmate.moderation.LinkReview;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Schema(description = "Everything a reviewer needs to decide about one payment link")
public record LinkDetail(
    LinkSummary link,
    @Schema(description = "Why it isn't shown, in words", nullable = true) String statusReason,
    @Schema(description = "Scam reports, newest first (up to 200). Who reported is never shown.") List<Report> reports,
    @Schema(description = "Rooms where it was shown, most recent first (up to 50)") List<Room> rooms,
    @Schema(description = "Give taps on this link and what viewers said happened") Gifts gifts,
    @Schema(description = "Past decisions, newest first") List<Review> history
) {

    public record Report(
        @Schema(nullable = true, example = "Not our church's account") String reason,
        Instant reportedAt,
        @Schema(description = "USER or GUEST") String reporterKind,
        @Schema(description = "Room it was reported in", nullable = true) String roomTitle,
        @Schema(description = "A past decision already dealt with it") boolean reviewed
    ) {
    }

    public record Room(UUID spaceId, String title, long timesShown, Instant lastShownAt,
                       @Schema(description = "Put up by the AI watching the stream") boolean byAi,
                       @Schema(description = "Put up by a host by hand") boolean byHost) {
    }

    public record Gifts(long opened, long completed, long failed, long cancelled, long unknown) {
    }

    public record Review(UUID id, LinkReview.Decision decision, PaymentLink.Status fromStatus,
                         PaymentLink.Status toStatus, String note, String reviewerName, int reportsReviewed,
                         Instant decidedAt) {

        public static Review from(LinkReview review) {
            return new Review(review.getId(), review.getDecision(), review.getFromStatus(), review.getToStatus(),
                review.getNote(), review.getReviewerName(), review.getReportsReviewed(), review.getDecidedAt());
        }
    }
}
