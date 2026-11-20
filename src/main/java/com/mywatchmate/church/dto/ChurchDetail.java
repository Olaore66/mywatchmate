package com.mywatchmate.church.dto;

import com.mywatchmate.church.Church;
import com.mywatchmate.church.ChurchReview;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Schema(description = "Everything staff need to decide about a church")
public record ChurchDetail(
    ChurchResponse church,
    @Schema(description = "Who applied (contact them only through the church's official channels to verify)",
        nullable = true) Applicant appliedBy,
    @Schema(nullable = true) ChurchChangeResponse pendingChange,
    @Schema(description = "Gifts paid to this church inside rooms") GiftCounts gifts,
    @Schema(description = "Past decisions, newest first") List<Review> history,
    @Schema(description = "What to check before approving") List<String> checklist
) {
    public record Applicant(UUID userId, String displayName, String email) {
    }

    public record GiftCounts(long opened, long completed, long failed, long cancelled, long unknown) {
    }

    public record Review(UUID id, ChurchReview.Decision decision, Church.Status fromStatus, Church.Status toStatus,
                         String note, String reviewerName, Instant decidedAt) {

        public static Review from(ChurchReview r) {
            return new Review(r.getId(), r.getDecision(), r.getFromStatus(), r.getToStatus(), r.getNote(),
                r.getReviewerName(), r.getDecidedAt());
        }
    }
}
