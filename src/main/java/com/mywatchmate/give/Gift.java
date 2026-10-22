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
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One tap of the Give button. The outcome comes back from the provider's SDK
 * in the viewer's app, so UNKNOWN is a legitimate final state.
 */
@Entity
@Table(name = "gifts")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Gift {

    public enum Status { OPENED, COMPLETED, FAILED, CANCELLED, UNKNOWN }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "detection_id")
    private CueDetection detection;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "session_id")
    private WatchSession session;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "participant_id")
    private Participant participant;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "payment_link_id")
    private PaymentLink paymentLink;

    @Column(name = "is_anonymous", nullable = false)
    private boolean anonymous;

    // Minor units (kobo, cents); null until the SDK reports it
    private Long amountMinor;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(length = 3)
    private String currency;

    @Column(length = 128)
    private String providerTxnRef;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status = Status.OPENED;

    @Column(nullable = false, updatable = false)
    private Instant openedAt = Instant.now();

    private Instant completedAt;

    public Gift(CueDetection detection, WatchSession session, Participant participant,
                PaymentLink paymentLink, boolean anonymous) {
        this.detection = detection;
        this.session = session;
        this.participant = participant;
        this.paymentLink = paymentLink;
        this.anonymous = anonymous;
    }
}
