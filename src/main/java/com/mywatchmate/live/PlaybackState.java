package com.mywatchmate.live;

import com.mywatchmate.watch.ContentItem;
import com.mywatchmate.watch.Participant;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
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

/**
 * Where a room's shared video is: "as of {@code positionAt} it was at {@code positionMs}, PLAYING or PAUSED".
 * Nobody's player is the boss; everyone works out where they should be from this.
 * Every change bumps {@code version}, so the latest press wins and stale messages can be ignored.
 */
@Entity
@Table(name = "playback_states")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PlaybackState {

    public enum Status { STOPPED, PLAYING, PAUSED }

    @Id
    private UUID spaceId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "content_id")
    private ContentItem content;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status = Status.STOPPED;

    @Column(nullable = false)
    private long positionMs;

    @Column(nullable = false)
    private Instant positionAt;

    @Column(nullable = false)
    private long version;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "updated_by")
    private Participant updatedBy;

    /** The state of a room nobody has pressed anything in yet: paused at the start of what it shows. */
    public static PlaybackState initial(UUID spaceId, ContentItem content, Instant now) {
        PlaybackState state = new PlaybackState();
        state.spaceId = spaceId;
        state.content = content;
        state.status = content == null ? Status.STOPPED : Status.PAUSED;
        state.positionAt = now;
        return state;
    }

    /** Where the video is at {@code now}: it keeps moving while playing, and stops at the end. */
    public long positionAt(Instant now) {
        long position = positionMs;
        if (status == Status.PLAYING && now.isAfter(positionAt)) {
            position += Duration.between(positionAt, now).toMillis();
        }
        Long length = lengthMs();
        return length == null ? position : Math.min(position, length);
    }

    /** A known-length video that has run to its end; shown as paused there. */
    public boolean hasEnded(Instant now) {
        Long length = lengthMs();
        return status == Status.PLAYING && length != null && positionAt(now) >= length;
    }

    /** What clients are told: a finished video is PAUSED at its end, not "playing" past it. */
    public Status statusAt(Instant now) {
        return hasEnded(now) ? Status.PAUSED : status;
    }

    /** A new video in the room: start it from the beginning for everyone (or stop, when there's none). */
    public void load(ContentItem newContent, Instant now) {
        content = newContent;
        status = newContent == null ? Status.STOPPED : Status.PLAYING;
        positionMs = 0;
        positionAt = now;
        updatedBy = null;
        version++;
    }

    /** Play from where it is; pressing play on a finished video starts it again. */
    public void play(Instant now, Participant by) {
        long position = isAtEnd(now) ? 0 : positionAt(now);
        change(Status.PLAYING, position, now, by);
    }

    public void pause(Instant now, Participant by) {
        change(Status.PAUSED, positionAt(now), now, by);
    }

    /** Jump; keeps playing if it was playing. A finished video stays paused at the new spot. */
    public void seek(long toMs, Instant now, Participant by) {
        change(hasEnded(now) ? Status.PAUSED : status, toMs, now, by);
    }

    /** The video's length, when we know it. */
    public Long lengthMs() {
        if (content == null || content.isLive() || content.getDurationSeconds() == null) {
            return null;
        }
        return content.getDurationSeconds() * 1000L;
    }

    private boolean isAtEnd(Instant now) {
        Long length = lengthMs();
        return length != null && positionAt(now) >= length;
    }

    private void change(Status newStatus, long newPositionMs, Instant now, Participant by) {
        status = newStatus;
        positionMs = newPositionMs;
        positionAt = now;
        updatedBy = by;
        version++;
    }
}
