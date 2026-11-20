package com.mywatchmate.church;

import com.mywatchmate.give.PayazaAccount;
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
import java.util.List;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** A verified church asking for a new key, mode or links. The church keeps its current ones until staff approve. */
@Entity
@Table(name = "church_changes")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChurchChange {

    public enum Status { PENDING, APPROVED, REJECTED }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "church_id", updatable = false)
    private Church church;

    // Null: unchanged
    @Column(name = "payaza_public_key", length = 200, updatable = false)
    private String publicKey;

    @Enumerated(EnumType.STRING)
    @Column(length = 8, updatable = false)
    private PayazaAccount.Mode mode;

    // Normalised payment links; null: unchanged
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "payment_links", columnDefinition = "text[]", updatable = false)
    private List<String> paymentLinks;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status = Status.PENDING;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "requested_by", updatable = false)
    private User requestedBy;

    @Column(nullable = false, updatable = false)
    private Instant requestedAt;

    private Instant decidedAt;

    public ChurchChange(Church church, String publicKey, PayazaAccount.Mode mode, List<String> paymentLinks,
                        User requestedBy, Instant now) {
        this.church = church;
        this.publicKey = publicKey;
        this.mode = mode;
        this.paymentLinks = paymentLinks;
        this.requestedBy = requestedBy;
        this.requestedAt = now;
    }
}
