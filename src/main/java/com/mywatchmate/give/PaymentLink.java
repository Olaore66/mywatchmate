package com.mywatchmate.give;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
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

    public enum Status { ALLOWED, BLOCKED }

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

    @Column(nullable = false, updatable = false)
    private Instant firstSeenAt = Instant.now();

    @Column(nullable = false)
    private Instant lastSeenAt = Instant.now();

    public PaymentLink(String normalizedUrl, Provider provider) {
        this.normalizedUrl = normalizedUrl;
        this.provider = provider;
    }
}
