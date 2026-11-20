package com.mywatchmate.auth;

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
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** An outside account (Google) that signs a user in. */
@Entity
@Table(name = "user_identities")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserIdentity {

    public enum Provider { GOOGLE }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", updatable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16, updatable = false)
    private Provider provider;

    // The provider's permanent id for the account ("sub")
    @Column(nullable = false, updatable = false)
    private String subject;

    @Column(nullable = false, length = 320)
    private String email;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    public UserIdentity(User user, Provider provider, String subject, String email, Instant createdAt) {
        this.user = user;
        this.provider = provider;
        this.subject = subject;
        this.email = email;
        this.createdAt = createdAt;
    }
}
