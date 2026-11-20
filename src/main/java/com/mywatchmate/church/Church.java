package com.mywatchmate.church;

import com.mywatchmate.give.PayazaAccount;
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
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A church registered with MyWatchMate: its Payaza PUBLIC key and its own payment links. Once staff verify it, those
 * links are paid inside any room. PENDING → VERIFIED or REJECTED; VERIFIED ⇄ SUSPENDED.
 */
@Entity
@Table(name = "churches")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Church {

    public enum Status { PENDING, VERIFIED, REJECTED, SUSPENDED }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(length = 500)
    private String website;

    @Column(length = 320)
    private String officialEmail;

    @Column(length = 40)
    private String officialPhone;

    @Column(name = "payaza_public_key", nullable = false, length = 200)
    private String publicKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private PayazaAccount.Mode mode;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 3)
    private String currencyCode = "NGN";

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status = Status.PENDING;

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(name = "church_links", joinColumns = @JoinColumn(name = "church_id"),
        inverseJoinColumns = @JoinColumn(name = "payment_link_id"))
    private Set<PaymentLink> links = new LinkedHashSet<>();

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "applied_by")
    private User appliedBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "verified_by")
    private User verifiedBy;

    private Instant verifiedAt;

    @Column(nullable = false)
    private Instant statusChangedAt;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Version
    @Column(nullable = false)
    private long version;

    public Church(String name, User appliedBy, Instant now) {
        this.name = name;
        this.appliedBy = appliedBy;
        this.createdAt = now;
        this.statusChangedAt = now;
    }

    void changeStatus(Status to, Instant now) {
        status = to;
        statusChangedAt = now;
    }
}
