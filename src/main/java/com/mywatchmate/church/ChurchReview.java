package com.mywatchmate.church;

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

/** One staff decision about a church. Never changed or removed (the database refuses). */
@Entity
@Immutable
@Table(name = "church_reviews")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChurchReview {

    public enum Decision { APPROVE, REJECT, SUSPEND, REINSTATE, APPROVE_CHANGE, REJECT_CHANGE }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "church_id", updatable = false)
    private Church church;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reviewer_id", updatable = false)
    private User reviewer;

    @Column(nullable = false, length = 100, updatable = false)
    private String reviewerName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24, updatable = false)
    private Decision decision;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16, updatable = false)
    private Church.Status fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16, updatable = false)
    private Church.Status toStatus;

    @Column(nullable = false, length = 1000, updatable = false)
    private String note;

    @Column(nullable = false, updatable = false)
    private Instant decidedAt;

    ChurchReview(Church church, User reviewer, Decision decision, Church.Status from, Church.Status to, String note,
                 Instant decidedAt) {
        this.church = church;
        this.reviewer = reviewer;
        this.reviewerName = reviewer.getDisplayName();
        this.decision = decision;
        this.fromStatus = from;
        this.toStatus = to;
        this.note = note;
        this.decidedAt = decidedAt;
    }
}
