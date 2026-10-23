package com.mywatchmate.watch;

import com.mywatchmate.common.ApiException;
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
import org.hibernate.annotations.CreationTimestamp;

/**
 * One sitting of a space: SCHEDULED → LIVE → ENDED, or SCHEDULED → CANCELLED.
 * An ENDED session that never started was missed (startedAt is null).
 * Live playback position lives in Redis, not here.
 */
@Entity
@Table(name = "watch_sessions")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WatchSession {

    public enum Status { SCHEDULED, LIVE, ENDED, CANCELLED }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "space_id")
    private Space space;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "schedule_id")
    private Schedule schedule;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "content_item_id")
    private ContentItem contentItem;

    @Column(length = 150)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status = Status.SCHEDULED;

    private Instant scheduledAt;

    private Instant startedAt;

    private Instant endedAt;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    public WatchSession(Space space, ContentItem contentItem, Instant scheduledAt) {
        this.space = space;
        this.contentItem = contentItem;
        this.scheduledAt = scheduledAt;
    }

    /** A sitting generated from a repeating schedule. */
    public static WatchSession fromSchedule(Schedule schedule, Instant scheduledAt) {
        WatchSession session = new WatchSession(schedule.getSpace(), schedule.getContentItem(), scheduledAt);
        session.schedule = schedule;
        session.title = schedule.getTitle();
        return session;
    }

    public boolean isScheduled() {
        return status == Status.SCHEDULED;
    }

    public boolean isLive() {
        return status == Status.LIVE;
    }

    public void start(Instant now) {
        requireStatus(Status.SCHEDULED, "Only a scheduled session can start.");
        status = Status.LIVE;
        startedAt = now;
    }

    public void end(Instant now) {
        requireStatus(Status.LIVE, "Only a live session can end.");
        status = Status.ENDED;
        endedAt = now;
    }

    public void cancel() {
        requireStatus(Status.SCHEDULED, "Only a scheduled session can be cancelled.");
        status = Status.CANCELLED;
    }

    /** Its start time passed long ago without it ever starting (e.g. the room was archived). */
    public void markMissed(Instant now) {
        requireStatus(Status.SCHEDULED, "Only a scheduled session can be missed.");
        status = Status.ENDED;
        endedAt = now;
    }

    /**
     * When a live session should end on its own: the video's length plus a grace period when known,
     * otherwise the maximum length (YouTube lengths and live streams are unknown).
     */
    public Instant autoEndAt(Duration maxLength, Duration grace) {
        ContentItem content = contentItem;
        if (content != null && !content.isLive() && content.getDurationSeconds() != null) {
            return startedAt.plusSeconds(content.getDurationSeconds()).plus(grace);
        }
        return startedAt.plus(maxLength);
    }

    private void requireStatus(Status expected, String message) {
        if (status != expected) {
            throw ApiException.conflict("SESSION_WRONG_STATUS", message);
        }
    }
}
