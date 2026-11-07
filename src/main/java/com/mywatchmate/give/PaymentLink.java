package com.mywatchmate.give;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A payment link decoded from a QR code on the stream, checked once and cached.
 * The link owner never needs a WatchWithMe account.
 */
@Entity
@Table(name = "payment_links")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PaymentLink {

    public enum Provider { PAYAZA }

    /**
     * ALLOWED: fine to show (never held, or cleared by staff). HELD: reported by enough viewers and waiting for
     * review; not shown meanwhile. BLOCKED: confirmed as a scam by staff.
     */
    public enum Status { ALLOWED, HELD, BLOCKED }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String normalizedUrl;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private Provider provider;

    // From the provider, if it exposes one
    @Column(length = 200)
    private String merchantName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status = Status.ALLOWED;

    private String blockedReason;

    // For a HELD link: how long it has waited for review
    @Column(nullable = false)
    private Instant statusChangedAt = Instant.now();

    @Column(nullable = false, updatable = false)
    private Instant firstSeenAt = Instant.now();

    @Column(nullable = false)
    private Instant lastSeenAt = Instant.now();

    // Viewers who said "this looks like a scam" since the last review; enough of them and it's held
    @Column(nullable = false)
    private int reportCount;

    // A viewer's write can't silently undo a reviewer's decision
    @Version
    @Column(nullable = false)
    private long version;

    public PaymentLink(String normalizedUrl, Provider provider) {
        this.normalizedUrl = normalizedUrl;
        this.provider = provider;
    }

    /** Not to be shown anywhere: held for review, or confirmed as a scam. */
    public boolean isBlocked() {
        return status != Status.ALLOWED;
    }

    public void changeStatus(Status to, String reason, Instant now) {
        status = to;
        blockedReason = to == Status.ALLOWED ? null : reason;
        statusChangedAt = now;
    }
}
