package com.mywatchmate.give;

import com.mywatchmate.watch.Participant;
import com.mywatchmate.watch.WatchSession;
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
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Every give cue the AI spotted on a stream, whether or not a button was shown. */
@Entity
@Table(name = "cue_detections")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CueDetection {

    public enum CueType { QR, LINK, SPOKEN }

    public enum Status { SHOWN, REJECTED, BLOCKED }

    public enum Source { AI, HOST }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "session_id")
    private WatchSession session;

    // Null if the cue wasn't a valid payment link
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "payment_link_id")
    private PaymentLink paymentLink;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private CueType cueType = CueType.QR;

    @Column(nullable = false)
    private String rawValue;

    @Column(precision = 4, scale = 3)
    private BigDecimal confidence;

    private Integer contentPositionSeconds;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status;

    @Column(length = 100)
    private String rejectReason;

    @Column(nullable = false, updatable = false)
    private Instant detectedAt = Instant.now();

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private Source source = Source.AI;

    // The host who put it up by hand
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "shown_by")
    private Participant shownBy;

    // A SHOWN prompt is up until then, unless closed (by a host, or replaced by a newer one)
    private Instant expiresAt;

    private Instant closedAt;

    public CueDetection(WatchSession session, CueType cueType, String rawValue, Status status) {
        this.session = session;
        this.cueType = cueType;
        this.rawValue = rawValue;
        this.status = status;
    }

    public boolean isShowing(Instant now) {
        return status == Status.SHOWN && closedAt == null && expiresAt != null && expiresAt.isAfter(now);
    }

    public void close(Instant now) {
        if (closedAt == null) {
            closedAt = now;
        }
    }
}
