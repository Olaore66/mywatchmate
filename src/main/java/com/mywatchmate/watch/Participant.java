package com.mywatchmate.watch;

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
import lombok.Setter;

/**
 * Anyone in a space: a registered user or a guest. A guest who signs up
 * later keeps their history by having {@code user} set on this row.
 */
@Entity
@Table(name = "participants")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Participant {

    public enum Role { OWNER, ADMIN, MEMBER }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "space_id")
    private Space space;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    @Column(length = 100)
    private String guestDisplayName;

    // Hash of the device token stored on the guest's device
    @Column(length = 128)
    private String guestTokenHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Role role = Role.MEMBER;

    @Column(nullable = false)
    private Instant joinedAt = Instant.now();

    private Instant leftAt;

    // Set when an owner/admin removed this person; they can't rejoin from the link
    private Instant removedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "removed_by")
    private Participant removedBy;

    public static Participant member(Space space, User user, Role role) {
        Participant p = new Participant();
        p.space = space;
        p.user = user;
        p.role = role;
        return p;
    }

    public static Participant guest(Space space, String displayName, String tokenHash) {
        Participant p = new Participant();
        p.space = space;
        p.guestDisplayName = displayName;
        p.guestTokenHash = tokenHash;
        return p;
    }

    public boolean isGuest() {
        return user == null;
    }

    /** Currently in the room (has not left or been removed). */
    public boolean isActive() {
        return leftAt == null;
    }

    public boolean wasRemoved() {
        return removedAt != null;
    }

    public String displayName() {
        return user != null ? user.getDisplayName() : guestDisplayName;
    }

    public void leave(Instant now) {
        if (leftAt == null) {
            leftAt = now;
        }
    }

    public void remove(Instant now, Participant by) {
        leave(now);
        removedAt = now;
        removedBy = by;
    }

    /** Back in after leaving. Roles aren't remembered: a returning admin is a member again. */
    public void rejoin(Instant now) {
        leftAt = null;
        joinedAt = now;
        role = Role.MEMBER;
    }

    public void changeRole(Role newRole) {
        this.role = newRole;
    }

    /** A guest who registered: their history stays, and their guest device token stops working. */
    public void attachToUser(User user) {
        this.user = user;
        this.guestDisplayName = null;
        this.guestTokenHash = null;
    }
}
