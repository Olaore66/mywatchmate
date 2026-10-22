package com.mywatchmate.give;

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

    public CueDetection(WatchSession session, CueType cueType, String rawValue, Status status) {
        this.session = session;
        this.cueType = cueType;
        this.rawValue = rawValue;
        this.status = status;
    }
}
