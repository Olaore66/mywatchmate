package com.mywatchmate.identity;

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
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User {

    /** MyWatchMate staff: can review reported payment links. Granted by deploy/grant-staff.sh only. */
    public enum StaffRole { MODERATOR, ADMIN }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 320)
    private String email;

    @Column(nullable = false, length = 100)
    private String displayName;

    // Null for social login
    private String passwordHash;

    @Column(nullable = false, length = 64)
    private String timezone = "UTC";

    // Master switch for reminder emails across all rooms
    @Column(nullable = false)
    private boolean emailReminders = true;

    // When they proved this address is theirs; null until then
    private Instant emailVerifiedAt;

    // Null for everyone except MyWatchMate staff
    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private StaffRole staffRole;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private Instant updatedAt;

    public boolean isStaff() {
        return staffRole != null;
    }

    public boolean isEmailVerified() {
        return emailVerifiedAt != null;
    }

    /** Keeps the first time. */
    public void markEmailVerified(Instant now) {
        if (emailVerifiedAt == null) {
            emailVerifiedAt = now;
        }
    }

    public User(String email, String displayName) {
        this.email = email;
        this.displayName = displayName;
    }
}
