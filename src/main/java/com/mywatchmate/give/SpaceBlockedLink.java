package com.mywatchmate.give;

import com.mywatchmate.watch.Participant;
import com.mywatchmate.watch.Space;
import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** A room owner or admin blocking a payment link in their own space only. */
@Entity
@Table(name = "space_blocked_links")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SpaceBlockedLink {

    @EmbeddedId
    private SpaceBlockedLinkId id;

    @MapsId("spaceId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "space_id")
    private Space space;

    @MapsId("paymentLinkId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "payment_link_id")
    private PaymentLink paymentLink;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "blocked_by")
    private Participant blockedBy;

    private String reason;

    @Column(nullable = false, updatable = false)
    private Instant blockedAt = Instant.now();

    public SpaceBlockedLink(Space space, PaymentLink paymentLink, Participant blockedBy, String reason) {
        this.id = new SpaceBlockedLinkId(space.getId(), paymentLink.getId());
        this.space = space;
        this.paymentLink = paymentLink;
        this.blockedBy = blockedBy;
        this.reason = reason;
    }
}
