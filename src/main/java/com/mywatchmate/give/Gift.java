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

    // When the viewer's app reported the result
    private Instant reportedAt;

    // Paid in the room through Payaza's checkout: our reference for it, and the key and mode it used
    @Column(length = 64, updatable = false)
    private String checkoutReference;

    @Column(length = 200, updatable = false)
    private String checkoutPublicKey;

    @Enumerated(EnumType.STRING)
    @Column(length = 8, updatable = false)
    private PayazaAccount.Mode checkoutMode;

    // The verified church it went to (null: a room's own registration, or a new-tab gift)
    @Column(name = "church_id", updatable = false)
    private UUID churchId;

    // Payaza confirmed the result (in-room checkout); false: as the giver's app reported it
    @Column(nullable = false)
    private boolean verified;

    public Gift(CueDetection detection, WatchSession session, Participant participant,
                PaymentLink paymentLink, boolean anonymous) {
        this.detection = detection;
        this.session = session;
        this.participant = participant;
        this.paymentLink = paymentLink;
        this.anonymous = anonymous;
    }

    /** Paid through Payaza's checkout in the room, so Payaza can confirm it. */
    public boolean isInRoomCheckout() {
        return checkoutReference != null;
    }

    /** Opened as an in-room checkout into this account. */
    public void useCheckout(String reference, CheckoutAccount account) {
        this.checkoutReference = reference;
        this.checkoutPublicKey = account.publicKey();
        this.checkoutMode = account.mode();
        this.churchId = account.churchId();
    }

    public void useCheckout(String reference, PayazaAccount room) {
        useCheckout(reference, CheckoutAccount.of(room));
    }

    /** What Payaza says happened: the truth, whatever the app reported. */
    public void confirm(Status result, Long amountMinor, String currency, String payazaReference, Instant now) {
        report(result, amountMinor, currency, payazaReference, now);
        this.verified = true;
    }

    /** A result can be reported once; a late one is still accepted after the gift went UNKNOWN. */
    public boolean awaitsResult() {
        return status == Status.OPENED || (status == Status.UNKNOWN && reportedAt == null);
    }

    public void report(Status result, Long amountMinor, String currency, String providerTxnRef, Instant now) {
        this.status = result;
        this.amountMinor = amountMinor;
        this.currency = currency;
        this.providerTxnRef = providerTxnRef;
        this.reportedAt = now;
        this.completedAt = result == Status.COMPLETED ? now : null;
    }
}
