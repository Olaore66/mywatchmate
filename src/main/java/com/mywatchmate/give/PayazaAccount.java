package com.mywatchmate.give;

import com.mywatchmate.watch.Participant;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
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
 * A room's church on Payaza: its PUBLIC key (what Payaza's checkout popup needs, made to be shared) and the payment
 * links that are its own. Used only in this room.
 */
@Entity
@Table(name = "payaza_accounts")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PayazaAccount {

    /** Payaza's test or live environment (the SDK's connection_mode). */
    public enum Mode {
        TEST("Test"), LIVE("Live");

        private final String sdkName;

        Mode(String sdkName) {
            this.sdkName = sdkName;
        }

        public String sdkName() {
            return sdkName;
        }
    }

    @Id
    private UUID spaceId;

    @Column(nullable = false, length = 200)
    private String publicKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private Mode mode;

    @Column(nullable = false, length = 200)
    private String merchantName;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 3)
    private String currencyCode = "NGN";

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(name = "payaza_account_links", joinColumns = @JoinColumn(name = "space_id"),
        inverseJoinColumns = @JoinColumn(name = "payment_link_id"))
    private Set<PaymentLink> links = new LinkedHashSet<>();

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "updated_by")
    private Participant updatedBy;

    @Column(nullable = false)
    private Instant updatedAt;

    public PayazaAccount(UUID spaceId) {
        this.spaceId = spaceId;
    }

    /** For showing who it's registered to without printing the whole key. */
    public String maskedKey() {
        return mask(publicKey);
    }

    public static String mask(String key) {
        return key.length() <= 12 ? "…" + key.substring(Math.max(0, key.length() - 4))
            : key.substring(0, 8) + "…" + key.substring(key.length() - 4);
    }
}
