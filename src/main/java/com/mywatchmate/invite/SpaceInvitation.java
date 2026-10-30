package com.mywatchmate.invite;

import com.mywatchmate.watch.Participant;
import com.mywatchmate.watch.Space;
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
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One address invited to a room by email: PENDING (queued) → SENT → JOINED, or FAILED / CANCELLED / EXPIRED.
 * The email carries the room's ordinary invite link; this row is how we track it.
 */
@Entity
@Table(name = "space_invitations")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SpaceInvitation {

    public enum Status { PENDING, SENT, JOINED, FAILED, CANCELLED, EXPIRED }

    static final int MAX_ERROR = 300;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "space_id")
    private Space space;

    @Column(nullable = false, length = 254)
    private String email;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "invited_by")
    private Participant invitedBy;

    @Column(length = 500)
    private String message;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status = Status.PENDING;

    @Column(nullable = false)
    private int attempts;

    @Column(length = MAX_ERROR)
    private String lastError;

    private Instant nextAttemptAt;

    @Column(nullable = false)
    private Instant lastQueuedAt;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    private Instant sentAt;

    private Instant joinedAt;

    @Column(nullable = false)
    private Instant expiresAt;

    public SpaceInvitation(Space space, String email, Participant invitedBy, String message, Instant now, Duration lifetime) {
        this.space = space;
        this.email = email;
        this.invitedBy = invitedBy;
        this.message = message;
        this.createdAt = now;
        queue(now, lifetime);
    }

    /** Still waiting to be sent or joined (and not past its expiry). */
    public boolean isLive(Instant now) {
        return (status == Status.PENDING || status == Status.SENT) && expiresAt.isAfter(now);
    }

    /** What clients are shown: a live invite past its expiry is EXPIRED even before the job marks it. */
    public Status statusAt(Instant now) {
        return (status == Status.PENDING || status == Status.SENT) && !expiresAt.isAfter(now) ? Status.EXPIRED : status;
    }

    /** Queue (again): sent by the job as soon as it next runs, with a fresh expiry. */
    public void queue(Instant now, Duration lifetime) {
        status = Status.PENDING;
        attempts = 0;
        lastError = null;
        nextAttemptAt = now;
        lastQueuedAt = now;
        expiresAt = now.plus(lifetime);
    }

    /** The job took it to send now. */
    public void sending(Instant now) {
        attempts++;
        status = Status.SENT;
        sentAt = now;
        nextAttemptAt = null;
    }

    /** Sending failed: try again later, or give up after {@code maxAttempts}. */
    public void sendFailed(String error, Instant now, int maxAttempts, Duration retryDelay) {
        lastError = error == null ? "unknown error" : error.length() > MAX_ERROR ? error.substring(0, MAX_ERROR) : error;
        sentAt = null;
        if (attempts >= maxAttempts) {
            status = Status.FAILED;
            nextAttemptAt = null;
        } else {
            status = Status.PENDING;
            nextAttemptAt = now.plus(retryDelay.multipliedBy(attempts));
        }
    }

    public void joined(Instant now) {
        status = Status.JOINED;
        joinedAt = now;
        nextAttemptAt = null;
    }

    public void cancel() {
        status = Status.CANCELLED;
        nextAttemptAt = null;
    }

    public void expire() {
        status = Status.EXPIRED;
        nextAttemptAt = null;
    }
}
