package com.mywatchmate.moderation;

import com.mywatchmate.give.PaymentLink;
import com.mywatchmate.identity.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

/**
 * One staff decision about a payment link. Append-only: never changed or removed (the database refuses), so
 * it answers "who blocked this, when and why" for good.
 */
@Entity
@Immutable
@Table(name = "link_reviews")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LinkReview {

    public enum Decision { CONFIRM_SCAM, CLEAR }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "payment_link_id", updatable = false)
    private PaymentLink paymentLink;

    // Null once their account is deleted; the name stays
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reviewer_id", updatable = false)
    private User reviewer;

    @Column(nullable = false, length = 100, updatable = false)
    private String reviewerName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16, updatable = false)
    private Decision decision;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16, updatable = false)
    private PaymentLink.Status fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16, updatable = false)
    private PaymentLink.Status toStatus;

    @Column(nullable = false, length = 500, updatable = false)
    private String note;

    // Reports this decision dealt with (they no longer count towards holding the link)
    @Column(nullable = false, updatable = false)
    private int reportsReviewed;

    @Column(nullable = false, updatable = false)
    private Instant decidedAt;

    LinkReview(PaymentLink link, User reviewer, Decision decision, PaymentLink.Status from, PaymentLink.Status to,
               String note, int reportsReviewed, Instant decidedAt) {
        this.paymentLink = link;
        this.reviewer = reviewer;
        this.reviewerName = reviewer.getDisplayName();
        this.decision = decision;
        this.fromStatus = from;
        this.toStatus = to;
        this.note = note;
        this.reportsReviewed = reportsReviewed;
        this.decidedAt = decidedAt;
    }
}
